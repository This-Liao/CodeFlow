#!/usr/bin/env python3
"""Record CodeFlow solving and verifying a real isolated repository task."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import yaml

from record_e2e_demo import render_terminal_gif

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / ".codeflow" / "config.yaml"
DEFAULT_GIF = ROOT / "docs" / "assets" / "codeflow-demo.gif"
DEFAULT_EVIDENCE = ROOT / "docs" / "demo" / "product-demo.json"


def create_fixture(root: Path) -> None:
    files = {
        "src/retry_policy.py": '''def retry_delay(attempt: int) -> int:
    """Return retry delay in milliseconds."""
    return attempt * 100
''',
        "tests/test_retry_policy.py": '''import unittest

from src.retry_policy import retry_delay


class RetryPolicyTest(unittest.TestCase):
    def test_exponential_delay_is_capped(self):
        self.assertEqual([100, 200, 400, 800, 800],
                         [retry_delay(i) for i in range(1, 6)])

    def test_attempt_must_be_positive(self):
        with self.assertRaises(ValueError):
            retry_delay(0)


if __name__ == "__main__":
    unittest.main()
''',
    }
    for relative, content in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def load_model(config: Path) -> str:
    data = yaml.safe_load(config.read_text(encoding="utf-8")) or {}
    providers = data.get("providers") or []
    if not providers:
        raise RuntimeError("provider config has no providers")
    return str(providers[0].get("model") or "configured model")


def parse_events(output: str) -> list[dict[str, Any]]:
    events = []
    for line in output.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            events.append(value)
    if not any(event.get("type") == "result" for event in events):
        raise RuntimeError("CodeFlow did not return a final result event")
    return events


def relative_path(value: Any, fixture: Path) -> str:
    text = str(value or "")
    try:
        return str(Path(text).resolve().relative_to(fixture.resolve())).replace("\\", "/")
    except (OSError, ValueError):
        return text.replace(str(fixture), ".").replace("\\", "/")


def terminal_lines(events: list[dict[str, Any]], fixture: Path, model: str) -> list[str]:
    lines = [
        "$ codeflow -p \"fix retry policy and run tests\"",
        "",
        f"model: {model}",
        "task : fix exponential backoff, input validation, and the 800ms cap",
        "",
    ]
    for event in events:
        kind = event.get("type")
        if kind == "tool_use":
            name = str(event.get("tool_name") or "Tool")
            args = event.get("args") or {}
            if name in {"ReadFile", "EditFile", "WriteFile"}:
                path = relative_path(args.get("file_path") or args.get("path"), fixture)
                lines.append(f"● {name}  {path}")
            elif name == "Bash":
                command = str(args.get("command") or args.get("cmd") or "run verification")
                lines.append(f"● Bash  {command}")
            else:
                lines.append(f"● {name}")
        elif kind == "tool_result":
            name = str(event.get("tool_name") or "Tool")
            if event.get("is_error"):
                lines.append(f"  ✗ {name} failed")
            elif name in {"EditFile", "WriteFile"}:
                lines.append("  ✓ patch applied")
            elif name == "ReadFile":
                lines.append("  ✓ source loaded")
            elif name == "Bash":
                output = str(event.get("output") or "")
                summary = next(
                    (line.strip() for line in output.splitlines() if line.strip() == "OK"),
                    "verification passed",
                )
                lines.append(f"  ✓ tests: {summary}")
            else:
                lines.append(f"  ✓ {name} completed")

    result = next(event for event in reversed(events) if event.get("type") == "result")
    final_text = str(result.get("result") or "").strip()
    if not final_text:
        raise RuntimeError("CodeFlow returned an empty final answer")
    summary = []
    for raw in final_text.splitlines():
        cleaned = raw.strip().lstrip("#*- ").strip()
        if cleaned and all(ord(char) < 128 for char in cleaned):
            summary.append(cleaned[:96])
        if len(summary) == 3:
            break
    lines.extend(["", "CodeFlow completed"])
    lines.extend(f"  {line}" for line in summary)
    lines.extend([
        "  ✓ independent unit-test verification: OK",
        "",
        "Real model • real tool calls • real patch • real tests",
    ])
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--gif", type=Path, default=DEFAULT_GIF)
    parser.add_argument("--evidence", type=Path, default=DEFAULT_EVIDENCE)
    args = parser.parse_args()
    config = args.config.resolve()
    if not config.is_file():
        raise RuntimeError(f"config not found: {config}")
    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")

    work = ROOT / ".codeflow" / "product-demo"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    create_fixture(work)
    model = load_model(config)
    prompt = (
        "Fix src/retry_policy.py. retry_delay(attempt) must use exponential backoff "
        "starting at 100ms, cap at 800ms, and reject attempts below 1. You must run "
        "python -m unittest discover -s tests -v. Do not modify tests. Finish with a "
        "concise English summary."
    )
    started = time.perf_counter()
    try:
        run = subprocess.run(
            ["java", "-jar", str(jar), str(config), "-p", prompt,
             "--output-format", "stream-json"],
            cwd=work,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
        )
        if run.returncode:
            raise RuntimeError(f"CodeFlow demo failed: {run.stdout[-1000:]}")
        events = parse_events(run.stdout)
        verify = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
            cwd=work,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
        )
        if verify.returncode or "OK" not in verify.stdout:
            raise RuntimeError(f"final independent verification failed: {verify.stdout[-800:]}")

        lines = terminal_lines(events, work, model)
        render_terminal_gif(
            lines,
            args.gif,
            title="CodeFlow — real repository task",
            footer="Real model • real tool calls • real patch • real tests",
        )
        tools = [
            str(event.get("tool_name")) for event in events
            if event.get("type") == "tool_use"
        ]
        result = next(event for event in reversed(events) if event.get("type") == "result")
        evidence = {
            "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
            "result": "PASS",
            "model": model,
            "task": "fix exponential retry policy and run unit tests",
            "toolCalls": tools,
            "toolErrors": sum(
                event.get("type") == "tool_result" and event.get("is_error") is True
                for event in events
            ),
            "independentVerification": "PASS",
            "durationMs": round((time.perf_counter() - started) * 1000),
            "usage": result.get("usage") or {},
        }
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"Product demo: {args.gif.relative_to(ROOT)}")
        print(f"Evidence    : {args.evidence.relative_to(ROOT)}")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"Product demo not recorded: {exc}")
        raise SystemExit(2) from None
