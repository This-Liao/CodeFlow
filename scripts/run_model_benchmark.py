#!/usr/bin/env python3
"""Run an optional paid-model Context Policy comparison outside CI."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[1]
TASKS = [
    ("Read PaymentService.java and return the exact validation token only.", "TOKEN_RETRY_7"),
    ("Use Grep to find FEATURE_FLAG_ALPHA and return its validation token only.", "TOKEN_ALPHA_ENABLED"),
    ("Read config/application.properties and return its timeout validation token only.", "TOKEN_TIMEOUT_4500"),
    ("Read reports/test-result.txt and return its test validation token only.", "TOKEN_TESTS_PASS"),
]


def find_config(explicit: Path | None) -> Path:
    candidates = ([explicit] if explicit else []) + [
        ROOT / ".codeflow/config.yaml",
        Path.home() / ".codeflow/config.yaml",
    ]
    for candidate in candidates:
        if candidate and candidate.is_file():
            return candidate.resolve()
    raise RuntimeError("No provider config found. Create .codeflow/config.yaml or pass --config.")


def create_fixture(root: Path) -> None:
    files = {
        "src/PaymentService.java": 'class PaymentService { String token = "TOKEN_RETRY_7"; }\n',
        "src/FeatureFlags.java": (
            'class FeatureFlags { boolean FEATURE_FLAG_ALPHA = true; '
            'String token = "TOKEN_ALPHA_ENABLED"; }\n'
        ),
        "config/application.properties": "timeout.ms=4500\nvalidation=TOKEN_TIMEOUT_4500\n",
        "reports/test-result.txt": "tests=PASS\nvalidation=TOKEN_TESTS_PASS\n",
    }
    for relative, content in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def write_config(source: Path, target: Path, enabled: bool) -> tuple[str, str]:
    data = yaml.safe_load(source.read_text(encoding="utf-8")) or {}
    providers = data.get("providers") or []
    if not providers:
        raise RuntimeError("provider config has no providers")
    provider = providers[0]
    protocol = str(provider.get("protocol", ""))
    env_name = {
        "openai": "OPENAI_API_KEY",
        "openai-compat": "OPENAI_API_KEY",
        "anthropic": "ANTHROPIC_API_KEY",
    }.get(protocol)
    if not provider.get("api_key") and (not env_name or not os.getenv(env_name)):
        raise RuntimeError(f"No API credential available; set {env_name or 'provider api_key'}.")
    isolated = {
        "providers": providers,
        "enable_coordinator_mode": False,
        "enable_fork": False,
        "a2a_agents": [],
        "mcp_servers": [],
        "hooks": [],
        "context_policy": {
            "enabled": enabled,
            "max_tool_schemas": 12,
            "max_schema_chars": 16000,
            "max_memory_chars": 6000,
        },
    }
    target.write_text(yaml.safe_dump(isolated, sort_keys=False), encoding="utf-8")
    return str(provider.get("name", "provider")), str(provider.get("model", "unknown"))


def result_event(output: str) -> tuple[dict[str, Any], int]:
    events = []
    for line in output.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    result = next((item for item in reversed(events) if item.get("type") == "result"), None)
    if result is None:
        raise RuntimeError("CodeFlow did not return a result")
    errors = sum(
        item.get("type") == "tool_result" and item.get("is_error") is True
        for item in events
    )
    return result, errors


def run_variant(name: str, config: Path, fixture: Path, jar: Path) -> dict[str, Any]:
    rows = []
    for index, (request, expected) in enumerate(TASKS, 1):
        prompt = "Use repository tools in read-only mode. Do not edit or create files. " + request
        started = time.perf_counter()
        run = subprocess.run(
            ["java", "-jar", str(jar), str(config), "-p", prompt,
             "--output-format", "stream-json"],
            cwd=fixture, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", timeout=240,
        )
        if run.returncode:
            raise RuntimeError(f"{name} task {index} failed: {run.stdout[-800:]}")
        result, errors = result_event(run.stdout)
        usage = result.get("usage") or {}
        row = {
            "success": expected in str(result.get("result") or ""),
            "inputTokens": int(usage.get("input_tokens") or 0),
            "outputTokens": int(usage.get("output_tokens") or 0),
            "latencyMs": round((time.perf_counter() - started) * 1000),
            "toolCalls": len(result.get("tool_calls") or []),
            "toolErrors": errors,
        }
        rows.append(row)
        print(f"[{name}] {index}/{len(TASKS)} {'PASS' if row['success'] else 'FAIL'}")
    latencies = sorted(row["latencyMs"] for row in rows)
    return {
        "name": name,
        "successRate": round(100 * sum(row["success"] for row in rows) / len(rows), 1),
        "averageInputTokens": round(sum(row["inputTokens"] for row in rows) / len(rows)),
        "totalInputTokens": sum(row["inputTokens"] for row in rows),
        "totalOutputTokens": sum(row["outputTokens"] for row in rows),
        "p95LatencyMs": latencies[-1],
        "toolCalls": sum(row["toolCalls"] for row in rows),
        "toolErrors": sum(row["toolErrors"] for row in rows),
        "tasks": rows,
    }


def write_report(report: dict[str, Any], json_path: Path, markdown_path: Path) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = [
        "| {name} | {successRate:.1f}% | {averageInputTokens} | {totalInputTokens} | "
        "{p95LatencyMs} ms | {toolCalls} | {toolErrors} |".format(**item)
        for item in report["variants"]
    ]
    markdown_path.write_text(
        f"""# 真实模型 Context Benchmark

> CodeFlow JAR 调用实际付费模型，在隔离只读 Fixture 上运行；报告不包含 Key、Base URL 或回答正文。

模型：{report['model']}；任务：{report['taskCount']} 条；时间：{report['timestampUtc']}

| Variant | Success | Avg Input Tokens | Total Input Tokens | p95 Latency | Tool Calls | Tool Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
{chr(10).join(rows)}

结果受模型版本与采样影响，作为 Semantic Layer 证据，不替代 CI 确定性门禁。
""", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path)
    parser.add_argument("--json", type=Path, default=ROOT / "docs/model-benchmarks/latest.json")
    parser.add_argument("--markdown", type=Path, default=ROOT / "docs/MODEL_BENCHMARK.md")
    args = parser.parse_args()
    source = find_config(args.config)
    jar = ROOT / "build/libs/codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")
    work = ROOT / ".codeflow/model-benchmark"
    shutil.rmtree(work, ignore_errors=True)
    fixture = work / "fixture"
    fixture.mkdir(parents=True)
    create_fixture(fixture)
    try:
        full = work / "full.yaml"
        context = work / "context.yaml"
        provider, model = write_config(source, full, False)
        write_config(source, context, True)
        variants = [
            run_variant("full-tool-context", full, fixture, jar),
            run_variant("stage-aware-context", context, fixture, jar),
        ]
        report = {
            "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
            "result": "PASS", "provider": provider, "model": model,
            "taskCount": len(TASKS), "variants": variants,
        }
        write_report(report, args.json, args.markdown)
        print(f"Model benchmark: {args.markdown.relative_to(ROOT)}")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"Model benchmark not run: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
