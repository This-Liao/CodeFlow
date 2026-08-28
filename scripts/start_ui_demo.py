#!/usr/bin/env python3
"""Prepare and run an isolated real-model task in the CodeFlow Remote UI."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

from run_coding_benchmark import TASKS, prepare_task
from run_model_benchmark import find_config, write_config

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVIDENCE = ROOT / "docs" / "demo" / "ui-demo.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--port", type=int, default=18991)
    parser.add_argument("--evidence", type=Path, default=DEFAULT_EVIDENCE)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise RuntimeError("port must be between 1024 and 65535")
    source_config = find_config(args.config)
    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")

    work = (ROOT / ".codeflow" / "ui-demo").resolve()
    allowed = (ROOT / ".codeflow").resolve()
    if work.parent != allowed:
        raise RuntimeError("refusing to clean an unexpected runtime directory")
    shutil.rmtree(work, ignore_errors=True)
    repo = work / "repo"
    repo.mkdir(parents=True)
    task = TASKS[0]
    prepare_task(repo, task)
    config = work / "config.yaml"
    _, model = write_config(source_config, config, True)
    verify = [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"]
    prompt = (
        f"修复 src/{task.module}.py：实现指数退避，初始延迟为 100 ms，上限为 800 ms；"
        "当 attempt 小于 1 时拒绝执行。不要修改测试。请先检查仓库、完成代码修改，并在结束前"
        f"运行以下验证命令：{subprocess.list2cmdline(verify)}。"
        "全过程使用中文回复，包括进度说明、工具调用前后的说明和最终回答。"
    )
    print()
    print(f"CodeFlow Remote UI: http://localhost:{args.port}")
    print("Paste this task into the page:")
    print(prompt)
    print()
    print("After the UI shows Done, stop this process with Ctrl+C.")
    process = subprocess.Popen(
        ["java", "-jar", str(jar), str(config), f"--remote=:{args.port}"],
        cwd=repo,
    )
    try:
        process.wait()
    except KeyboardInterrupt:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)

    checked = subprocess.run(
        verify, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", timeout=60,
    )
    passed = checked.returncode == 0
    evidence = {
        "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
        "result": "PASS" if passed else "FAIL",
        "model": model,
        "task": "fix exponential retry policy and run unit tests in Remote UI",
        "remoteUi": f"http://localhost:{args.port}",
        "independentVerification": "PASS" if passed else "FAIL",
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Independent verification: {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"UI demo not started: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
