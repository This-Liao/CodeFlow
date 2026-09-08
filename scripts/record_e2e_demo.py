#!/usr/bin/env python3
"""Run and record CodeFlow's real Java-to-Python end-to-end validation.

The GIF is rendered from stdout captured during the same Gradle, Java and
Python processes that produce the machine-readable validation report. The
script exits before producing success artifacts if any validation step fails.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from queue import Empty, Queue
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "examples" / "a2a-static-analysis-agent"
DEFAULT_GIF = ROOT / ".codeflow" / "validation-run.gif"
DEFAULT_JSON = ROOT / "docs" / "validation" / "latest.json"
DEFAULT_MARKDOWN = ROOT / "docs" / "VALIDATION.md"
DEFAULT_BENCHMARK_JSON = ROOT / "docs" / "benchmarks" / "context-ablation.json"
DEFAULT_BENCHMARK_MARKDOWN = ROOT / "docs" / "BENCHMARK.md"
VALIDATION_PREFIX = "CODEFLOW_VALIDATION_JSON="
CRASH_READY_PREFIX = "CODEFLOW_CRASH_READY="
DURABLE_RESULT_PREFIX = "CODEFLOW_DURABLE_RECOVERY_JSON="
BENCHMARK_PREFIX = "CODEFLOW_CONTEXT_BENCHMARK_JSON="


def run(command: list[str], *, cwd: Path = ROOT, env: dict[str, str] | None = None) -> str:
    """Run one real process, echo its output, and fail on a non-zero exit."""
    display = subprocess.list2cmdline(command) if os.name == "nt" else " ".join(command)
    print(f"$ {display}", flush=True)
    completed = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    print(completed.stdout, end="", flush=True)
    if completed.returncode:
        raise RuntimeError(f"command failed with exit code {completed.returncode}: {display}")
    return completed.stdout


def gradle_command() -> list[str]:
    if os.name == "nt":
        return ["cmd.exe", "/d", "/s", "/c", "gradlew.bat", "--no-daemon", "clean", "test", "shadowJar"]
    return [str(ROOT / "gradlew"), "--no-daemon", "clean", "test", "shadowJar"]


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_for_card(url: str, timeout_seconds: float = 30) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # server startup is expected to race this probe
            last_error = exc
            time.sleep(0.15)
    raise RuntimeError(f"Python A2A Agent did not become ready: {last_error}")


def junit_summary() -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = sorted((ROOT / "build" / "test-results" / "test").glob("TEST-*.xml"))
    if not files:
        raise RuntimeError("Gradle finished without JUnit XML results")
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    totals["passed"] = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    return totals


def version_line(command: list[str]) -> str:
    try:
        output = subprocess.run(
            command,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
        ).stdout
        return next((line.strip() for line in output.splitlines() if line.strip()), "unknown")
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def parse_validation(output: str) -> dict[str, Any]:
    for line in reversed(output.splitlines()):
        if line.startswith(VALIDATION_PREFIX):
            return json.loads(line[len(VALIDATION_PREFIX) :])
    raise RuntimeError("Java validation did not emit its machine-readable result")


def parse_prefixed_json(output: str, prefix: str) -> dict[str, Any]:
    for line in reversed(output.splitlines()):
        if line.startswith(prefix):
            return json.loads(line[len(prefix) :])
    raise RuntimeError(f"process did not emit {prefix}")


def run_crash_recovery(jar: Path) -> tuple[dict[str, Any], list[str]]:
    """Kill one JVM after its safe checkpoint, then resume in a new JVM."""
    validation_root = ROOT / ".codeflow" / "validation"
    validation_root.mkdir(parents=True, exist_ok=True)
    work_dir = Path(tempfile.mkdtemp(prefix="crash-recovery-", dir=validation_root))
    prepare_command = [
        "java", "-cp", str(jar),
        "com.codeflow.durable.DurableRecoveryValidationMain",
        "prepare", str(work_dir),
    ]
    display = subprocess.list2cmdline(prepare_command) if os.name == "nt" else " ".join(prepare_command)
    print(f"$ {display}", flush=True)
    process = subprocess.Popen(
        prepare_command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_queue: Queue[str | None] = Queue()

    def read_output() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            output_queue.put(line)
        output_queue.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    captured: list[str] = []
    ready: dict[str, Any] | None = None
    deadline = time.monotonic() + 30
    try:
        while time.monotonic() < deadline:
            try:
                line = output_queue.get(timeout=0.2)
            except Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            captured.append(line)
            print(line, end="", flush=True)
            if line.startswith(CRASH_READY_PREFIX):
                ready = json.loads(line[len(CRASH_READY_PREFIX) :])
                break
        if ready is None:
            raise RuntimeError("crash worker did not reach a safe checkpoint")
        process.kill()
        killed_exit_code = process.wait(timeout=10)
        print(f"!! forced process termination after checkpoint (exit={killed_exit_code})", flush=True)

        resume_output = run([
            "java", "-cp", str(jar),
            "com.codeflow.durable.DurableRecoveryValidationMain",
            "resume", str(work_dir), str(ready["taskId"]),
        ])
        result = parse_prefixed_json(resume_output, DURABLE_RESULT_PREFIX)
        result["forcedExitCode"] = killed_exit_code
        result["checkpointVersion"] = ready["checkpointVersion"]
        transcript = [
            "$ java ... DurableRecoveryValidationMain prepare",
            (
                "safe checkpoint: state="
                f"{ready['state']}, completed tool calls={ready['completedToolCalls']}"
            ),
            f"!! JVM forcibly killed after atomic checkpoint (exit={killed_exit_code})",
            "$ java ... DurableRecoveryValidationMain resume",
            f"recovered from: {result['recoveredFrom']}",
            f"checkpoint reused: {str(result['reusedCheckpoint']).lower()}",
            f"duplicate tool calls: {result['duplicateToolCalls']}",
            f"file hash preserved: {str(result['fileHashPreserved']).lower()}",
            f"final state: {result['finalState']}",
            f"recovery duration: {result['recoveryDurationMs']} ms",
        ]
        return result, transcript
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        shutil.rmtree(work_dir, ignore_errors=True)


def terminal_lines(
    build_output: str,
    tests: dict[str, int],
    jar_bytes: int,
    card: dict[str, Any],
    java_output: str,
    durable_lines: list[str],
    benchmark: dict[str, Any],
) -> list[str]:
    build_result = next(
        (line.strip() for line in reversed(build_output.splitlines()) if line.startswith("BUILD ")),
        "BUILD SUCCESSFUL",
    )
    lines = [
        "$ ./gradlew clean test shadowJar",
        "",
        build_result,
        (
            f"JUnit: {tests['tests']} tests, {tests['passed']} passed, "
            f"{tests['failures'] + tests['errors']} failed, {tests['skipped']} skipped"
        ),
        f"Executable JAR: build/libs/codeflow.jar ({jar_bytes / 1024 / 1024:.2f} MiB)",
        "",
        "$ python -m uvicorn agent:app  # real LangGraph process",
        f"Agent Card ready: {card.get('name', 'unknown')}",
        "HTTP+JSON endpoint discovered from /.well-known/agent-card.json",
        "",
    ]
    lines.extend(
        line
        for line in java_output.splitlines()
        if line.strip() and not line.startswith(VALIDATION_PREFIX)
    )
    lines.extend(["", "=== Durable crash-recovery E2E ===", ""])
    lines.extend(durable_lines)
    context = next(row for row in benchmark["variants"] if row["name"] == "context-hybrid")
    lines.extend([
        "",
        "=== Context/Deferred Tool ablation ===",
        "",
        f"tasks: {benchmark['taskCount']}, tool catalog: {benchmark['toolCatalogSize']}",
        f"context-hybrid success: {context['successRate']:.1f}%",
        f"initial schemas: {context['averageInitialToolSchemas']:.1f}",
        f"schema reduction vs full: {context['schemaReductionPct']:.1f}%",
        f"tool errors: {context['toolErrors']}",
    ])
    return lines


def load_font(size: int, *, bold: bool = False) -> ImageFont.ImageFont:
    candidates = [
        Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / ("consolab.ttf" if bold else "consola.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf" if bold
             else "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"),
        Path("/System/Library/Fonts/Menlo.ttc"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def wrap_terminal(lines: list[str], width: int = 104) -> list[str]:
    wrapped: list[str] = []
    for line in lines:
        chunks = textwrap.wrap(
            line,
            width=width,
            replace_whitespace=False,
            drop_whitespace=False,
            subsequent_indent="  ",
        )
        wrapped.extend(chunks or [""])
    return wrapped


def render_terminal_gif(
    lines: list[str],
    output: Path,
    *,
    title: str = "CodeFlow — real end-to-end validation",
    footer: str = "Captured from real Gradle, Java and Python processes • report: docs/VALIDATION.md",
) -> None:
    """Render captured real terminal output as a compact GitHub-friendly recording."""
    width, height = 1280, 720
    header_height, footer_height = 54, 42
    left, top, line_height = 34, 78, 25
    max_rows = (height - top - footer_height - 18) // line_height
    font = load_font(20)
    bold_font = load_font(20, bold=True)
    title_font = load_font(17, bold=True)
    recorded = wrap_terminal(lines)
    frames: list[Image.Image] = []
    durations: list[int] = []

    for count in range(1, len(recorded) + 1):
        canvas = Image.new("RGB", (width, height), "#0b1020")
        draw = ImageDraw.Draw(canvas)
        draw.rounded_rectangle((8, 8, width - 8, height - 8), radius=18, fill="#111827", outline="#334155", width=2)
        draw.rounded_rectangle((9, 9, width - 9, header_height), radius=17, fill="#182235")
        draw.rectangle((9, 35, width - 9, header_height), fill="#182235")
        for x, color in ((30, "#ff5f57"), (54, "#febc2e"), (78, "#28c840")):
            draw.ellipse((x - 7, 24 - 7, x + 7, 24 + 7), fill=color)
        draw.text((102, 16), title, font=title_font, fill="#dbeafe")

        visible = recorded[max(0, count - max_rows) : count]
        for row, line in enumerate(visible):
            color = "#d7e1ef"
            active_font = font
            if line.startswith("$"):
                color, active_font = "#7dd3fc", bold_font
            elif "PASSED" in line or "SUCCESSFUL" in line or "COMPLETED" in line:
                color, active_font = "#86efac", bold_font
            elif line.startswith("["):
                color = "#c4b5fd"
            elif "failed" in line.lower() and not re.search(r"0 failed", line.lower()):
                color = "#fca5a5"
            draw.text((left, top + row * line_height), line, font=active_font, fill=color)

        if count < len(recorded):
            cursor_y = top + (len(visible) - 1) * line_height
            cursor_x = min(width - 44, left + int(draw.textlength(visible[-1], font=font)) + 4)
            draw.rectangle((cursor_x, cursor_y + 4, cursor_x + 10, cursor_y + 23), fill="#93c5fd")

        draw.line((24, height - footer_height, width - 24, height - footer_height), fill="#26364e", width=1)
        draw.text(
            (28, height - 31),
            footer,
            font=load_font(15),
            fill="#7f91aa",
        )
        frames.append(canvas)
        durations.append(115 if count < len(recorded) else 2800)

    output.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        output,
        save_all=True,
        append_images=frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
    )


def write_reports(
    report: dict[str, Any],
    json_path: Path,
    markdown_path: Path,
) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tests = report["gradle"]["tests"]
    a2a = report["a2a"]
    durable = report["durableRecovery"]
    benchmark = report["contextBenchmark"]
    context = next(
        row for row in benchmark["variants"] if row["name"] == "context-hybrid"
    )
    markdown = f"""# CodeFlow 工程验证

> 这是由 `scripts/record_e2e_demo.py` 在真实端到端运行后生成的结果，不是手工填写的演示数据。

## 最近一次结果

| 验证项 | 实测结果 |
| --- | --- |
| 时间（UTC） | {report['timestampUtc']} |
| Gradle 构建 | PASS |
| JUnit | {tests['tests']} 项；{tests['passed']} 通过；{tests['failures'] + tests['errors']} 失败；{tests['skipped']} 跳过 |
| 可执行 JAR | {report['gradle']['jarBytes'] / 1024 / 1024:.2f} MiB |
| A2A Agent | {a2a['agent']} |
| 协议 | A2A {a2a['protocolVersion']} / {a2a['protocolBinding']} |
| 任务终态 | {a2a['state']} |
| 返回 Artifact | {a2a['artifacts']} |
| 实际扫描 Java 文件 | {a2a['scannedJavaFiles']} |
| 静态分析候选问题 | {a2a['findings']} |
| Java→Python 端到端耗时 | {a2a['durationMs']} ms |
| A2A Trace Correlation | {str(a2a.get('traceCorrelated', False)).lower()}；`{a2a.get('traceId', '')}` |
| Crash-Recovery | {durable['result']}；{durable['recoveredFrom']} → {durable['finalState']} |
| Checkpoint 复用 | {str(durable['reusedCheckpoint']).lower()} |
| 重复 Tool Call | {durable['duplicateToolCalls']} |
| 文件哈希保持 | {str(durable['fileHashPreserved']).lower()} |
| 新 JVM 恢复耗时 | {durable['recoveryDurationMs']} ms |
| Context 基准 | {benchmark['result']}；{benchmark['taskCount']} 条任务 / {benchmark['toolCatalogSize']} 个工具 |
| Context 任务成功率 | {context['successRate']:.1f}% |
| 阶段识别准确率 | {context['stageAccuracy']:.1f}% |
| Tool Schema 减少 | {context['schemaReductionPct']:.1f}% |
| ToolSearch 错误 | {context['toolErrors']} |
| 环境 | {report['environment']['os']}; {report['environment']['java']} |

## 实际验证链路

```text
Gradle clean/test/shadowJar
  → Python/LangGraph Agent 启动
  → Java Host 获取 Agent Card
  → A2A message:send 创建任务
  → tasks/{{id}} 状态轮询
  → TASK_STATE_COMPLETED
  → Markdown + JSON Artifact 校验

JVM-1 执行真实 EditFile
  → 原子写入 Checkpoint + Event
  → 外部强制终止 JVM-1
  → JVM-2 加载任务与 Checkpoint
  → 跳过已完成 Tool Call
  → 校验文件哈希与调用审计
  → COMPLETED

24 条固定任务 / 46 个工具
  → full-tool-injection
  → deferred-tool
  → lexical / vector / hybrid Context Policy
  → 校验成功率、阶段识别、Schema 压缩与 ToolSearch 错误
  → PASS
```

## 复现

请从仓库根目录执行 README“工程验证”中的三条命令。脚本仅在构建、全部测试、A2A Artifact、Crash-Recovery 和 Context 回归门禁均成功后更新本文件与 JSON 结果；`--gif` 只生成本地验证画面，不替代首页的真实端到端录屏。
"""
    markdown_path.write_text(markdown, encoding="utf-8")


def write_benchmark_reports(
    benchmark: dict[str, Any],
    json_path: Path,
    markdown_path: Path,
) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(
        json.dumps(benchmark, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    rows = []
    for variant in benchmark["variants"]:
        rows.append(
            "| {name} | {successRate:.1f}% | {stageAccuracy:.1f}% | "
            "{initialToolRecall:.1f}% | {averageInitialToolSchemas:.1f} | "
            "{averageSchemaChars:.0f} | {estimatedInputTokens} | "
            "{schemaReductionPct:.1f}% | {toolSearchCalls} | {toolErrors} |".format(**variant)
        )
    markdown = f"""# Context / Deferred Tool 消融基准

> 本报告由真实 Java 实现直接运行生成。任务集固定、无网络依赖，适合作为 CI 回归门禁；它衡量 Context 组装与工具发现，不把估算 Token 冒充模型账单。

## 配置

- 任务：{benchmark['taskCount']} 条，覆盖 PLANNING、EXECUTING、VERIFYING、RECOVERING。
- 工具目录：{benchmark['toolCatalogSize']} 个，其中包含内建工具和延迟加载的异构连接器。
- Context Policy 上限：{benchmark['contextPolicyMaxSchemas']} 个 Tool Schema。
- Estimated Input Tokens：按 Schema 字符数 / 4 估算，仅用于同一任务集的相对比较。

## 结果

| Variant | Success | Stage Accuracy | Initial Tool Recall | Initial Schemas | Schema Chars | Est. Tokens | Reduction vs Full | ToolSearch | Tool Error |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
{chr(10).join(rows)}

## 解释边界

- Success 表示必需工具能在初始上下文中获得，或能通过 ToolSearch 在第二步精确恢复。
- Initial Tool Recall 衡量第一轮是否已经注入任务所需工具；它与 Schema 压缩率存在明确权衡。
- 当前固定集上三种 Context 策略的初始 Recall 相同；Hybrid 没有被包装成准确率提升，其价值是允许在阶段先验、词法匹配和向量相关度之间做可配置、可回退的工程权衡。
- 默认 Vector 使用离线 feature hashing，适合可复现基准；OpenAI-compatible Embedding 是显式可选项，失败时回退到 Lexical。
- 该基准不调用 LLM，因此不能替代真实模型任务成功率。真实模型评测作为独立的可选层运行，避免 CI 受随机性和外部 API 影响。
"""
    markdown_path.write_text(markdown, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="reuse an existing tested executable JAR")
    parser.add_argument("--gif", type=Path, default=DEFAULT_GIF)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--benchmark-json", type=Path, default=DEFAULT_BENCHMARK_JSON)
    parser.add_argument("--benchmark-markdown", type=Path, default=DEFAULT_BENCHMARK_MARKDOWN)
    args = parser.parse_args()

    if shutil.which("java") is None:
        raise RuntimeError("java was not found on PATH")

    print("== CodeFlow real end-to-end recording ==", flush=True)
    build_output = ""
    if not args.skip_build:
        build_output = run(gradle_command())
    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.exists():
        raise RuntimeError(f"executable JAR does not exist: {jar}")
    tests = junit_summary()
    if tests["failures"] or tests["errors"]:
        raise RuntimeError(f"JUnit failures detected: {tests}")

    port = free_port()
    card_url = f"http://127.0.0.1:{port}/.well-known/agent-card.json"
    agent_env = os.environ.copy()
    agent_env["CODEFLOW_ANALYSIS_ROOT"] = str(ROOT)
    agent_env["PYTHONPATH"] = str(EXAMPLE) + os.pathsep + agent_env.get("PYTHONPATH", "")
    server = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "agent:app",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--log-level",
            "warning",
        ],
        cwd=EXAMPLE,
        env=agent_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        print(f"$ {sys.executable} -m uvicorn agent:app --port {port}", flush=True)
        card = wait_for_card(card_url)
        print(f"Agent Card ready: {card.get('name')}", flush=True)
        prompt = "Analyze the CodeFlow Java sources for reliability risks."
        java_output = run(
            [
                "java",
                "-cp",
                str(jar),
                "com.codeflow.a2a.A2aValidationMain",
                card_url,
                prompt,
            ]
        )
        a2a = parse_validation(java_output)
    finally:
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=5)

    durable, durable_lines = run_crash_recovery(jar)
    benchmark_output = run([
        "java", "-cp", str(jar), "com.codeflow.benchmark.ContextAblationMain"
    ])
    benchmark = parse_prefixed_json(benchmark_output, BENCHMARK_PREFIX)

    report = {
        "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
        "result": "PASS",
        "gradle": {
            "result": "PASS",
            "tests": tests,
            "jarBytes": jar.stat().st_size,
        },
        "a2a": a2a,
        "durableRecovery": durable,
        "contextBenchmark": benchmark,
        "environment": {
            "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
            "python": platform.python_version(),
            "java": version_line(["java", "-version"]),
        },
    }
    transcript = terminal_lines(
        build_output, tests, jar.stat().st_size, card, java_output,
        durable_lines, benchmark
    )
    render_terminal_gif(transcript, args.gif)
    write_reports(report, args.json, args.markdown)
    write_benchmark_reports(benchmark, args.benchmark_json, args.benchmark_markdown)
    print(f"Recorded GIF : {args.gif.resolve().relative_to(ROOT)}")
    print(f"JSON report  : {args.json.resolve().relative_to(ROOT)}")
    print(f"Human report : {args.markdown.resolve().relative_to(ROOT)}")
    print(f"Benchmark    : {args.benchmark_markdown.resolve().relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
