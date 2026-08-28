#!/usr/bin/env python3
"""Force-kill a real CodeFlow Agent after a safe turn checkpoint and resume it."""

from __future__ import annotations

import argparse
import hashlib
import json
import queue
import shutil
import subprocess
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from run_coding_benchmark import TASKS, prepare_task
from run_model_benchmark import find_config, write_config

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "docs" / "demo" / "crash-resume.json"
DEFAULT_MARKDOWN = ROOT / "docs" / "CRASH_RESUME.md"
MUTATION_TOOLS = {"EditFile", "WriteFile"}


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_json_lines(lines: list[str]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in lines:
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            events.append(item)
    return events


def start_reader(process: subprocess.Popen[str], output: list[str]) -> queue.Queue[str | None]:
    values: queue.Queue[str | None] = queue.Queue()

    def read() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            output.append(line)
            values.put(line)
        values.put(None)

    threading.Thread(target=read, daemon=True).start()
    return values


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def wait_for_safe_checkpoint(
    process: subprocess.Popen[str],
    values: queue.Queue[str | None],
    repo: Path,
    timeout: int,
) -> tuple[str, dict[str, Any]]:
    task_id: str | None = None
    mutation_seen = False
    tool_calls_seen = 0
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            line = values.get(timeout=0.1)
        except queue.Empty:
            line = ""
        if line is None and process.poll() is not None:
            break
        if line.startswith("Durable task: "):
            task_id = line.split(":", 1)[1].strip()
        if line.startswith("{"):
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                event = {}
            if event.get("type") == "tool_use":
                tool_calls_seen += 1
                mutation_seen = mutation_seen or event.get("tool_name") in MUTATION_TOOLS
        if task_id and mutation_seen:
            snapshot_path = repo / ".codeflow" / "tasks" / task_id / "task.json"
            if snapshot_path.is_file():
                snapshot = load_json(snapshot_path)
                checkpoint = snapshot.get("checkpoint") or {}
                if (
                    snapshot.get("state") == "EXECUTING"
                    and int(checkpoint.get("turn") or 0) >= 1
                    and int(checkpoint.get("toolCalls") or 0) >= tool_calls_seen
                ):
                    return task_id, snapshot
        if process.poll() is not None:
            break
    raise RuntimeError("Agent did not reach a post-mutation turn checkpoint before exit")


def tool_names(events: list[dict[str, Any]]) -> list[str]:
    return [
        str(event.get("tool_name"))
        for event in events
        if event.get("type") == "tool_use"
    ]


def usage(events: list[dict[str, Any]]) -> dict[str, int]:
    result = next((event for event in reversed(events) if event.get("type") == "result"), {})
    raw = result.get("usage") or {}
    return {
        "inputTokens": int(raw.get("input_tokens") or 0),
        "outputTokens": int(raw.get("output_tokens") or 0),
        "cacheReadTokens": int(raw.get("cache_read_tokens") or 0),
        "cacheCreationTokens": int(raw.get("cache_creation_tokens") or 0),
    }


def write_report(report: dict[str, Any], json_path: Path, markdown_path: Path) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(
        f"""# 真实 Agent Crash + Resume

> 两个 CodeFlow CLI 进程调用同一个真实模型。进程 1 在完成代码修改并写入安全 Turn Checkpoint 后被父进程强制终止；进程 2 使用同一 Task ID 和 Session 日志恢复。脚本最后独立运行测试。

| 证据 | 结果 |
| --- | --- |
| 模型 | `{report['model']}` |
| Task | `{report['taskId']}` |
| 崩溃前状态 / Checkpoint | {report['checkpoint']['state']} / turn {report['checkpoint']['turn']} |
| 外部终止 | exit={report['forcedExitCode']} |
| 新进程接管 | {str(report['recoveredByNewProcess']).lower()} |
| Session 历史复用 | {report['sessionMessagesBeforeResume']} 条消息 |
| 恢复进程重复修改 | {report['resumeMutationToolCalls']} 次 |
| 最终状态 | {report['finalState']} |
| 独立测试 | {report['independentVerification']} |
| 总耗时 | {report['durationMs'] / 1000:.1f}s |

机器可读证据见 `docs/demo/crash-resume.json`。报告不包含 Key、Base URL、Prompt、源码内容或模型回答正文。
""", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--timeout", type=int, default=420)
    args = parser.parse_args()
    source_config = find_config(args.config)
    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")

    work = (ROOT / ".codeflow" / "crash-resume-demo").resolve()
    allowed = (ROOT / ".codeflow").resolve()
    if work.parent != allowed:
        raise RuntimeError("refusing to clean an unexpected runtime directory")
    shutil.rmtree(work, ignore_errors=True)
    repo = work / "repo"
    repo.mkdir(parents=True)
    task = TASKS[0]
    source = prepare_task(repo, task)
    source_before = file_hash(source)
    tests_before = file_hash(repo / "tests" / f"test_{task.module}.py")
    config = work / "config.yaml"
    _, model = write_config(source_config, config, True)
    verify_command = [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"]
    prompt = (
        f"Fix src/{task.module}.py. {task.requirement} Do not modify tests. "
        "Run this exact verification command before finishing: "
        f"{subprocess.list2cmdline(verify_command)}"
    )
    started = time.perf_counter()
    first_lines: list[str] = []
    process = subprocess.Popen(
        ["java", "-jar", str(jar), str(config), "--durable", "-p", prompt,
         "--output-format", "stream-json"],
        cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    values = start_reader(process, first_lines)
    try:
        task_id, checkpoint_snapshot = wait_for_safe_checkpoint(
            process, values, repo, args.timeout
        )
        source_at_checkpoint = file_hash(source)
        if source_at_checkpoint == source_before:
            raise RuntimeError("mutation tool completed but source hash did not change")
        session_id = str(checkpoint_snapshot.get("sessionId") or "")
        session_path = repo / ".codeflow" / "sessions" / f"{session_id}.jsonl"
        session_messages = (
            sum(1 for line in session_path.read_text(encoding="utf-8").splitlines() if line.strip())
            if session_path.is_file() else 0
        )
        process.kill()
        forced_exit = process.wait(timeout=15)

        resume = subprocess.run(
            ["java", "-jar", str(jar), str(config), "--resume-task", task_id,
             "--output-format", "stream-json"],
            cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", timeout=args.timeout,
        )
        if resume.returncode != 0:
            raise RuntimeError("resume process failed")
        resume_lines = resume.stdout.splitlines()
        resume_events = parse_json_lines(resume_lines)
        if not any(event.get("type") == "result" for event in resume_events):
            raise RuntimeError("resume process did not return a result")
        verify = subprocess.run(
            verify_command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", timeout=60,
        )
        final_snapshot = load_json(
            repo / ".codeflow" / "tasks" / task_id / "task.json"
        )
        event_path = repo / ".codeflow" / "tasks" / task_id / "events.jsonl"
        lifecycle = [
            json.loads(line) for line in event_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        initial_events = parse_json_lines(first_lines)
        initial_tools = tool_names(initial_events)
        resume_tools = tool_names(resume_events)
        resume_mutations = sum(name in MUTATION_TOOLS for name in resume_tools)
        recovered = any(item.get("type") == "recovered" for item in lifecycle)
        tests_unchanged = file_hash(repo / "tests" / f"test_{task.module}.py") == tests_before
        passed = (
            recovered
            and final_snapshot.get("state") == "COMPLETED"
            and verify.returncode == 0
            and source_at_checkpoint == file_hash(source)
            and tests_unchanged
            and resume_mutations == 0
            and session_messages >= 3
        )
        checkpoint = checkpoint_snapshot.get("checkpoint") or {}
        report = {
            "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
            "result": "PASS" if passed else "FAIL",
            "model": model,
            "taskId": task_id,
            "checkpoint": {
                "state": checkpoint_snapshot.get("state"),
                "turn": int(checkpoint.get("turn") or 0),
                "toolCalls": int(checkpoint.get("toolCalls") or 0),
                "version": checkpoint_snapshot.get("version"),
            },
            "forcedExitCode": forced_exit,
            "recoveredByNewProcess": recovered,
            "sessionMessagesBeforeResume": session_messages,
            "initialToolNames": initial_tools,
            "resumeToolNames": resume_tools,
            "resumeMutationToolCalls": resume_mutations,
            "sourceHashPreservedAcrossResume": source_at_checkpoint == file_hash(source),
            "testsUnchanged": tests_unchanged,
            "finalState": final_snapshot.get("state"),
            "independentVerification": "PASS" if verify.returncode == 0 else "FAIL",
            "durationMs": round((time.perf_counter() - started) * 1000),
            "resumeUsage": usage(resume_events),
            "lifecycleEventTypes": [str(item.get("type")) for item in lifecycle],
        }
        write_report(report, args.json, args.markdown)
        print(f"Crash + resume: {'PASS' if passed else 'FAIL'}")
        print(f"Evidence      : {args.json.relative_to(ROOT)}")
        return 0 if passed else 2
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=15)
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"Crash + resume not run: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
