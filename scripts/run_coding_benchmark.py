#!/usr/bin/env python3
"""Run real-model repository edits against 12 isolated coding tasks."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from run_model_benchmark import find_config, write_config

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "docs" / "coding-benchmarks" / "latest.json"
DEFAULT_MARKDOWN = ROOT / "docs" / "CODING_BENCHMARK.md"


@dataclass(frozen=True)
class CodingTask:
    task_id: str
    category: str
    module: str
    requirement: str
    source: str
    tests: str


TASKS = [
    CodingTask(
        "retry-backoff", "state-and-boundary", "retry_policy",
        "Implement exponential retry delay from 100 ms, cap it at 800 ms, and reject attempts below 1.",
        """def retry_delay(attempt: int) -> int:
    \"\"\"Return retry delay in milliseconds.\"\"\"
    return attempt * 100
""",
        """import unittest
from src.retry_policy import retry_delay

class TestRetryPolicy(unittest.TestCase):
    def test_backoff_and_cap(self):
        self.assertEqual([100, 200, 400, 800, 800], [retry_delay(i) for i in range(1, 6)])
    def test_invalid_attempt(self):
        with self.assertRaises(ValueError): retry_delay(0)
"""),
    CodingTask(
        "pagination-bounds", "input-validation", "pagination",
        "Return zero-based start/end bounds for a one-based page. Validate page >= 1, 1 <= size <= 100, total >= 0, and clamp pages beyond the result set.",
        """def page_bounds(page: int, size: int, total: int) -> tuple[int, int]:
    start = page * size
    return start, min(start + size, total)
""",
        """import unittest
from src.pagination import page_bounds

class TestPagination(unittest.TestCase):
    def test_first_and_partial_pages(self):
        self.assertEqual((0, 20), page_bounds(1, 20, 45))
        self.assertEqual((40, 45), page_bounds(3, 20, 45))
        self.assertEqual((45, 45), page_bounds(9, 20, 45))
    def test_invalid_values(self):
        for args in [(0, 20, 1), (1, 0, 1), (1, 101, 1), (1, 20, -1)]:
            with self.assertRaises(ValueError): page_bounds(*args)
"""),
    CodingTask(
        "slug-normalization", "text-normalization", "slug",
        "Normalize ASCII text to a lowercase slug, collapse every run of non-alphanumeric characters to one hyphen, and strip edge hyphens.",
        """def slugify(text: str) -> str:
    return text.lower().replace(" ", "-")
""",
        """import unittest
from src.slug import slugify

class TestSlug(unittest.TestCase):
    def test_normalization(self):
        self.assertEqual("agent-harness-v2", slugify("  Agent  Harness v2! "))
        self.assertEqual("a-b", slugify("A___B"))
        self.assertEqual("", slugify("---"))
"""),
    CodingTask(
        "secret-redaction", "security", "redaction",
        "Redact Bearer token values and api_key assignments case-insensitively while preserving surrounding text and key names.",
        """def redact_secrets(text: str) -> str:
    return text.replace("token", "***")
""",
        """import unittest
from src.redaction import redact_secrets

class TestRedaction(unittest.TestCase):
    def test_bearer_and_api_key(self):
        value = "Authorization: Bearer abc.DEF-123; api_key = secret_456; safe=ok"
        out = redact_secrets(value)
        self.assertNotIn("abc.DEF-123", out)
        self.assertNotIn("secret_456", out)
        self.assertIn("Bearer ***", out)
        self.assertIn("api_key = ***", out)
        self.assertIn("safe=ok", out)
    def test_case_insensitive(self):
        self.assertEqual("BEARER ***", redact_secrets("BEARER TOPSECRET"))
"""),
    CodingTask(
        "duration-parser", "parsing", "duration",
        "Parse non-negative integer durations with ms, s, or m suffixes into milliseconds. Ignore surrounding whitespace and reject malformed values.",
        """def parse_duration(value: str) -> int:
    return int(value)
""",
        """import unittest
from src.duration import parse_duration

class TestDuration(unittest.TestCase):
    def test_units(self):
        self.assertEqual(250, parse_duration("250ms"))
        self.assertEqual(2000, parse_duration(" 2s "))
        self.assertEqual(180000, parse_duration("3m"))
    def test_invalid(self):
        for value in ["", "-1s", "1.5s", "12", "abc"]:
            with self.assertRaises(ValueError): parse_duration(value)
"""),
    CodingTask(
        "deep-config-merge", "data-structures", "config_merge",
        "Recursively merge nested dictionaries without mutating either input. Non-dictionary override values, including lists, replace the base value.",
        """def deep_merge(base: dict, override: dict) -> dict:
    return {**base, **override}
""",
        """import unittest
from src.config_merge import deep_merge

class TestMerge(unittest.TestCase):
    def test_nested_merge_and_replacement(self):
        base = {"agent": {"timeout": 10, "retry": 2}, "tools": ["read"]}
        override = {"agent": {"retry": 4}, "tools": ["read", "grep"]}
        self.assertEqual({"agent": {"timeout": 10, "retry": 4}, "tools": ["read", "grep"]}, deep_merge(base, override))
        self.assertEqual(2, base["agent"]["retry"])
"""),
    CodingTask(
        "dependency-order", "graph-algorithm", "dependency_order",
        "Return a deterministic topological order where dependencies precede dependents. Include dependency-only nodes and raise ValueError on a cycle.",
        """def dependency_order(graph: dict[str, list[str]]) -> list[str]:
    return list(graph)
""",
        """import unittest
from src.dependency_order import dependency_order

class TestDependencyOrder(unittest.TestCase):
    def test_dependencies_precede_dependents(self):
        order = dependency_order({"app": ["api", "db"], "api": ["db"]})
        self.assertEqual({"app", "api", "db"}, set(order))
        self.assertLess(order.index("db"), order.index("api"))
        self.assertLess(order.index("api"), order.index("app"))
    def test_cycle(self):
        with self.assertRaises(ValueError): dependency_order({"a": ["b"], "b": ["a"]})
"""),
    CodingTask(
        "stable-chunking", "collection-processing", "chunking",
        "Split a list into consecutive chunks of at most size items, preserving every item and order. Reject non-positive sizes.",
        """def chunks(items: list, size: int) -> list[list]:
    return [items[i:i + size] for i in range(0, len(items), size + 1)]
""",
        """import unittest
from src.chunking import chunks

class TestChunks(unittest.TestCase):
    def test_order_and_tail(self):
        self.assertEqual([[1, 2], [3, 4], [5]], chunks([1, 2, 3, 4, 5], 2))
        self.assertEqual([], chunks([], 3))
    def test_invalid_size(self):
        with self.assertRaises(ValueError): chunks([1], 0)
"""),
    CodingTask(
        "http-retry-classifier", "policy", "http_retry",
        "Return true only for transient HTTP statuses 408, 425, 429, 500, 502, 503, and 504.",
        """def is_retryable(status: int) -> bool:
    return status >= 500
""",
        """import unittest
from src.http_retry import is_retryable

class TestHttpRetry(unittest.TestCase):
    def test_transient_statuses(self):
        for status in [408, 425, 429, 500, 502, 503, 504]: self.assertTrue(is_retryable(status))
    def test_permanent_statuses(self):
        for status in [200, 400, 401, 404, 409, 501, 505]: self.assertFalse(is_retryable(status))
"""),
    CodingTask(
        "circuit-breaker", "state-machine", "circuit_breaker",
        "Open the breaker exactly when consecutive failures reach the threshold; reset the failure count and close it on success. Reject thresholds below 1.",
        """class CircuitBreaker:
    def __init__(self, threshold: int):
        self.threshold = threshold
        self.failures = 0
        self.is_open = False

    def record_failure(self) -> None:
        self.failures += 1
        if self.failures > self.threshold:
            self.is_open = True

    def record_success(self) -> None:
        pass
""",
        """import unittest
from src.circuit_breaker import CircuitBreaker

class TestBreaker(unittest.TestCase):
    def test_threshold_and_reset(self):
        breaker = CircuitBreaker(2)
        breaker.record_failure(); self.assertFalse(breaker.is_open)
        breaker.record_failure(); self.assertTrue(breaker.is_open)
        breaker.record_success(); self.assertFalse(breaker.is_open); self.assertEqual(0, breaker.failures)
    def test_invalid_threshold(self):
        with self.assertRaises(ValueError): CircuitBreaker(0)
"""),
    CodingTask(
        "header-normalization", "protocol-data", "headers",
        "Normalize header names to lowercase and trim values. Combine case-insensitive duplicates in encounter order with a comma and reject blank names.",
        """def normalize_headers(headers: dict[str, str]) -> dict[str, str]:
    return {key.lower(): value for key, value in headers.items()}
""",
        """import unittest
from src.headers import normalize_headers

class TestHeaders(unittest.TestCase):
    def test_normalize_and_combine(self):
        value = normalize_headers({" X-Trace ": " a ", "x-trace": "b", "Accept": " json "})
        self.assertEqual({"x-trace": "a,b", "accept": "json"}, value)
    def test_blank_name(self):
        with self.assertRaises(ValueError): normalize_headers({"  ": "x"})
"""),
    CodingTask(
        "safe-path-join", "filesystem-security", "safe_path",
        "Resolve a user path beneath a root directory and raise ValueError if it is absolute or escapes the root through parent traversal.",
        """from pathlib import Path

def safe_join(root: Path, user_path: str) -> Path:
    return root / user_path
""",
        """import tempfile
import unittest
from pathlib import Path
from src.safe_path import safe_join

class TestSafePath(unittest.TestCase):
    def test_child_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual((root / "docs" / "a.txt").resolve(), safe_join(root, "docs/a.txt"))
    def test_escape_and_absolute(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for value in ["../secret.txt", str((root.parent / "outside.txt").resolve())]:
                with self.assertRaises(ValueError): safe_join(root, value)
"""),
]


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare_task(root: Path, task: CodingTask) -> Path:
    source = root / "src" / f"{task.module}.py"
    tests = root / "tests" / f"test_{task.module}.py"
    source.parent.mkdir(parents=True, exist_ok=True)
    tests.parent.mkdir(parents=True, exist_ok=True)
    (source.parent / "__init__.py").write_text("", encoding="utf-8")
    source.write_text(task.source, encoding="utf-8")
    tests.write_text(task.tests, encoding="utf-8")
    return source


def parse_events(output: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in output.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            events.append(value)
    if not any(item.get("type") == "result" for item in events):
        raise RuntimeError("CodeFlow did not return a result event")
    return events


def run_one(task: CodingTask, root: Path, config: Path, jar: Path, timeout: int) -> dict[str, Any]:
    task_root = root / task.task_id
    shutil.rmtree(task_root, ignore_errors=True)
    source = prepare_task(task_root, task)
    test_file = task_root / "tests" / f"test_{task.module}.py"
    source_before = file_digest(source)
    tests_before = file_digest(test_file)
    verify_command = [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"]
    prompt = (
        f"Fix {source.relative_to(task_root).as_posix()}. {task.requirement} "
        "Do not modify tests. Inspect the repository, implement the fix, and run this exact "
        f"verification command before finishing: {subprocess.list2cmdline(verify_command)}"
    )
    started = time.perf_counter()
    run = subprocess.run(
        ["java", "-jar", str(jar), str(config), "-p", prompt,
         "--output-format", "stream-json"],
        cwd=task_root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )
    events = parse_events(run.stdout) if run.returncode == 0 else []
    verify = subprocess.run(
        verify_command, cwd=task_root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", timeout=60,
    )
    source_changed = file_digest(source) != source_before
    tests_unchanged = file_digest(test_file) == tests_before
    result = next((item for item in reversed(events) if item.get("type") == "result"), {})
    usage = result.get("usage") or {}
    tool_names = [str(item.get("tool_name")) for item in events if item.get("type") == "tool_use"]
    process_completed = run.returncode == 0 and bool(events)
    patch_correct = verify.returncode == 0 and source_changed and tests_unchanged
    success = process_completed and patch_correct
    return {
        "id": task.task_id, "category": task.category, "success": success,
        "processCompleted": process_completed, "processExitCode": run.returncode,
        "patchCorrect": patch_correct,
        "sourceChanged": source_changed, "testsUnchanged": tests_unchanged,
        "independentVerification": "PASS" if verify.returncode == 0 else "FAIL",
        "durationMs": round((time.perf_counter() - started) * 1000),
        "toolCalls": len(tool_names), "toolNames": tool_names,
        "toolErrors": sum(
            item.get("type") == "tool_result" and item.get("is_error") is True
            for item in events
        ),
        "usage": {
            "inputTokens": int(usage.get("input_tokens") or 0),
            "outputTokens": int(usage.get("output_tokens") or 0),
            "cacheReadTokens": int(usage.get("cache_read_tokens") or 0),
            "cacheCreationTokens": int(usage.get("cache_creation_tokens") or 0),
        },
    }


def percentile(values: list[int], ratio: float) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * ratio) - 1)] if ordered else 0


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    for item in rows:
        item.setdefault("processCompleted", bool(item.get("success")))
        item.setdefault(
            "patchCorrect",
            item.get("independentVerification") == "PASS"
            and bool(item.get("sourceChanged"))
            and bool(item.get("testsUnchanged")),
        )
    durations = [int(item["durationMs"]) for item in rows]
    passed = sum(bool(item["success"]) for item in rows)
    patches = sum(bool(item["patchCorrect"]) for item in rows)
    return {
        "total": len(rows), "passed": passed,
        "successRate": round(100 * passed / len(rows), 1),
        "patchesPassed": patches,
        "patchCorrectnessRate": round(100 * patches / len(rows), 1),
        "agentRunsCompleted": sum(bool(item["processCompleted"]) for item in rows),
        "independentVerificationPassed": sum(
            item["independentVerification"] == "PASS" for item in rows),
        "testsUnchanged": sum(bool(item["testsUnchanged"]) for item in rows),
        "p50LatencyMs": percentile(durations, 0.50),
        "p95LatencyMs": percentile(durations, 0.95),
        "toolCalls": sum(int(item["toolCalls"]) for item in rows),
        "toolErrors": sum(int(item["toolErrors"]) for item in rows),
    }


def write_report(report: dict[str, Any], json_path: Path, markdown_path: Path) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = [
        f"| `{item['id']}` | {item['category']} | "
        f"{'PASS' if item['success'] else ('PATCH PASS / AGENT INCOMPLETE' if item['patchCorrect'] else 'FAIL')} | "
        f"{item['durationMs'] / 1000:.1f}s | {item['toolCalls']} | {item['toolErrors']} |"
        for item in report["tasks"]
    ]
    summary = report["summary"]
    markdown_path.write_text(
        f"""# 真实模型 Coding Benchmark

> CodeFlow 使用真实模型在 {summary['total']} 个相互隔离的代码仓库中完成修改；脚本独立重跑测试，并校验源码确实变化且测试文件未被修改。报告不保存 Key、Base URL、Prompt 或模型回答正文。

模型：`{report['model']}`；时间：{report['timestampUtc']}

| Task | Category | Result | Duration | Tool Calls | Tool Errors |
| --- | --- | ---: | ---: | ---: | ---: |
{chr(10).join(rows)}

## 汇总

- 端到端 Agent 成功率：{summary['passed']}/{summary['total']}（{summary['successRate']:.1f}%）
- 正确 Patch：{summary['patchesPassed']}/{summary['total']}（{summary['patchCorrectnessRate']:.1f}%）
- 正常返回终态的 Agent 进程：{summary['agentRunsCompleted']}/{summary['total']}
- 独立测试通过：{summary['independentVerificationPassed']}/{summary['total']}
- 测试文件保持不变：{summary['testsUnchanged']}/{summary['total']}
- p50 / p95 任务耗时：{summary['p50LatencyMs'] / 1000:.1f}s / {summary['p95LatencyMs'] / 1000:.1f}s
- Tool Call / Tool Error：{summary['toolCalls']} / {summary['toolErrors']}

该套件覆盖边界处理、输入校验、文本与配置处理、图算法、状态机、协议数据和路径安全。“PATCH PASS / AGENT INCOMPLETE”表示源码和独立测试正确，但 Agent 进程没有正常返回终态；它仍按端到端失败计数。Tool Error 包含 Agent 主动运行失败测试后再修复的诊断步骤。

样本规模仍然有限，结果受模型版本、采样与网络状态影响，不作为默认 CI 门禁。
""", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=420)
    parser.add_argument("--limit", type=int, default=len(TASKS))
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    if args.report_only:
        report = json.loads(args.json.read_text(encoding="utf-8"))
        report["summary"] = summarize(report["tasks"])
        report["result"] = (
            "COMPLETE"
            if report["summary"]["patchesPassed"] == report["summary"]["total"]
            else "INCOMPLETE"
        )
        write_report(report, args.json, args.markdown)
        print(f"Coding benchmark report refreshed: {args.markdown.resolve()}")
        return 0
    source_config = find_config(args.config)
    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")
    selected = TASKS[:max(1, min(args.limit, len(TASKS)))]
    work = ROOT / ".codeflow" / "coding-benchmark"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    config = work / "config.yaml"
    provider, model = write_config(source_config, config, True)
    task_root = work / "tasks"
    task_root.mkdir()
    rows: list[dict[str, Any]] = []
    try:
        with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
            futures = {
                executor.submit(run_one, task, task_root, config, jar, args.timeout): task
                for task in selected
            }
            for future in as_completed(futures):
                task = futures[future]
                try:
                    row = future.result()
                except Exception as exc:
                    row = {
                        "id": task.task_id, "category": task.category, "success": False,
                        "sourceChanged": False, "testsUnchanged": True,
                        "independentVerification": "FAIL", "durationMs": 0,
                        "toolCalls": 0, "toolNames": [], "toolErrors": 0,
                        "usage": {"inputTokens": 0, "outputTokens": 0,
                                  "cacheReadTokens": 0, "cacheCreationTokens": 0},
                        "error": type(exc).__name__,
                    }
                rows.append(row)
                print(f"[{len(rows)}/{len(selected)}] {task.task_id}: "
                      f"{'PASS' if row['success'] else 'FAIL'}", flush=True)
        order = [task.task_id for task in selected]
        rows.sort(key=lambda item: order.index(item["id"]))
        summary = summarize(rows)
        report = {
            "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
            "result": (
                "COMPLETE"
                if summary["patchesPassed"] == summary["total"]
                else "INCOMPLETE"
            ),
            "provider": provider, "model": model, "summary": summary, "tasks": rows,
        }
        write_report(report, args.json, args.markdown)
        report_path = args.markdown.resolve()
        try:
            display_path = report_path.relative_to(ROOT)
        except ValueError:
            display_path = report_path
        print(f"Coding benchmark: {display_path}")
        return 0 if report["result"] == "COMPLETE" else 2
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"Coding benchmark not run: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
