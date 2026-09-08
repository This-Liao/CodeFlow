#!/usr/bin/env python3
"""Replay real coding tasks through the same Remote WebSocket path used by the browser UI."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from run_coding_benchmark import TASKS, file_digest, find_config, prepare_task, write_config

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "interactive-regression.json"
DEFAULT_JSON = ROOT / "docs" / "interactive-regressions" / "latest.json"
DEFAULT_MARKDOWN = ROOT / "docs" / "INTERACTIVE_REGRESSION.md"


class WebSocketClient:
    """Tiny RFC 6455 text client so the replay runner has no third-party dependency."""

    def __init__(self, host: str, port: int, path: str = "/ws") -> None:
        self.sock = socket.create_connection((host, port), timeout=10)
        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode("ascii"))
        response = self._recv_until(b"\r\n\r\n")
        if not response.startswith(b"HTTP/1.1 101"):
            raise RuntimeError("WebSocket upgrade failed: " + response[:200].decode(errors="replace"))
        accept = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
        ).digest())
        if accept not in response:
            raise RuntimeError("WebSocket accept key mismatch")

    def _recv_until(self, marker: bytes) -> bytes:
        data = bytearray()
        while marker not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("WebSocket closed during handshake")
            data.extend(chunk)
        return bytes(data)

    def send_json(self, value: dict[str, Any]) -> None:
        payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
        mask = secrets.token_bytes(4)
        header = bytearray([0x81])
        size = len(payload)
        if size < 126:
            header.append(0x80 | size)
        elif size < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", size))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", size))
        header.extend(mask)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.sock.sendall(header + masked)

    def receive_json(self, timeout: float) -> dict[str, Any]:
        self.sock.settimeout(timeout)
        while True:
            first, second = self._read_exact(2)
            opcode = first & 0x0F
            size = second & 0x7F
            if size == 126:
                size = struct.unpack("!H", self._read_exact(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self._read_exact(8))[0]
            masked = bool(second & 0x80)
            mask = self._read_exact(4) if masked else b""
            payload = self._read_exact(size)
            if masked:
                payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 0x8:
                raise RuntimeError("WebSocket closed before loop_complete")
            if opcode == 0x9:
                self._send_control(0xA, payload)
                continue
            if opcode != 0x1:
                continue
            value = json.loads(payload.decode("utf-8"))
            if isinstance(value, dict):
                return value

    def _send_control(self, opcode: int, payload: bytes) -> None:
        mask = secrets.token_bytes(4)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.sock.sendall(bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + masked)

    def _read_exact(self, size: int) -> bytes:
        data = bytearray()
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise RuntimeError("WebSocket connection closed")
            data.extend(chunk)
        return bytes(data)

    def close(self) -> None:
        try:
            self._send_control(0x8, b"")
        except OSError:
            pass
        self.sock.close()


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_server(port: int, process: subprocess.Popen[str], timeout: int = 30) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output = process.stdout.read() if process.stdout else ""
            raise RuntimeError("Remote Server exited during startup: " + output[-800:])
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(0.2)
    raise TimeoutError("Remote Server startup timed out")


def terminate(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def replay_case(case: dict[str, Any], task_by_id: dict[str, Any], task_root: Path,
                config: Path, jar: Path, timeout: int) -> dict[str, Any]:
    task = task_by_id[case["id"]]
    repo = task_root / task.task_id
    shutil.rmtree(repo, ignore_errors=True)
    source = prepare_task(repo, task)
    test_file = repo / "tests" / f"test_{task.module}.py"
    source_before = file_digest(source)
    tests_before = file_digest(test_file)
    verify_command = [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"]
    prompt = (
        f"修复 {source.relative_to(repo).as_posix()}。{task.requirement} "
        "不要修改测试。请先检查仓库、实现修改，并在结束前运行以下验证命令："
        f"{subprocess.list2cmdline(verify_command)}"
    )

    port = free_port()
    process = subprocess.Popen(
        ["java", "-jar", str(jar), str(config), f"--remote=127.0.0.1:{port}"],
        cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    events: list[dict[str, Any]] = []
    task_id = ""
    started = time.perf_counter()
    client: WebSocketClient | None = None
    error = ""
    server_output = ""
    try:
        wait_server(port, process)
        client = WebSocketClient("127.0.0.1", port)
        client.send_json({"type": "user_message", "data": {"content": prompt}})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            event = client.receive_json(max(1.0, deadline - time.monotonic()))
            events.append(event)
            if event.get("type") == "permission_request":
                request_id = str((event.get("data") or {}).get("id") or "")
                client.send_json({
                    "type": "permission_response",
                    "data": {"id": request_id, "response": "allow"},
                })
            elif event.get("type") == "ask_user":
                request_id = str((event.get("data") or {}).get("id") or "")
                client.send_json({
                    "type": "ask_user_response",
                    "data": {"id": request_id, "answers": {}},
                })
            if event.get("type") == "durable_task":
                task_id = str((event.get("data") or {}).get("id") or task_id)
            if event.get("type") == "loop_complete":
                break
        else:
            raise TimeoutError("interactive replay timed out")
    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"
    finally:
        if client is not None:
            client.close()
        terminate(process)
        if process.stdout is not None:
            server_output = process.stdout.read()
        if error and server_output.strip():
            diagnostic = " ".join(server_output.strip().splitlines()[-8:])[-1_500:]
            diagnostic = re.sub(r"(?i)(bearer|api[_-]?key|password)\s*[:=]?\s*\S+", r"\1=<redacted>", diagnostic)
            error += f" | remoteExit={process.returncode}: {diagnostic}"

    verify = subprocess.run(
        verify_command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", timeout=60,
    )
    event_types = {str(item.get("type")) for item in events}
    required = set(case.get("requiredEvents") or [])
    trace_files = list((repo / ".codeflow" / "traces").glob("*.json"))
    task_snapshot = repo / ".codeflow" / "tasks" / task_id / "task.json"
    durable_state = ""
    if task_snapshot.is_file():
        durable_state = str(json.loads(task_snapshot.read_text(encoding="utf-8")).get("state") or "")
    patch_correct = (
        verify.returncode == 0
        and file_digest(source) != source_before
        and file_digest(test_file) == tests_before
    )
    lifecycle_ok = required.issubset(event_types) and bool(trace_files) and durable_state == "COMPLETED"
    success = patch_correct and lifecycle_ok and not error
    return {
        "id": task.task_id,
        "category": task.category,
        "success": success,
        "patchCorrect": patch_correct,
        "lifecycleOk": lifecycle_ok,
        "requiredEvents": sorted(required),
        "observedEvents": sorted(event_types),
        "durableTaskId": task_id,
        "durableState": durable_state,
        "traceCount": len(trace_files),
        "testsUnchanged": file_digest(test_file) == tests_before,
        "independentVerification": "PASS" if verify.returncode == 0 else "FAIL",
        "durationMs": round((time.perf_counter() - started) * 1000),
        "toolCalls": sum(item.get("type") == "tool_use" for item in events),
        "toolErrors": sum(
            item.get("type") == "tool_result" and bool((item.get("data") or {}).get("isError"))
            for item in events
        ),
        "agentErrors": [
            str((item.get("data") or {}).get("message") or "")
            for item in events if item.get("type") == "error"
        ],
        **({"error": error} if error else {}),
    }


def write_report(report: dict[str, Any], json_path: Path, markdown_path: Path) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = "\n".join(
        f"| `{row['id']}` | {'PASS' if row['success'] else 'FAIL'} | "
        f"{row['durableState'] or '-'} | {row['traceCount']} | "
        f"{row['independentVerification']} | {row['durationMs'] / 1000:.1f}s |"
        for row in report["cases"]
    )
    summary = report["summary"]
    markdown_path.write_text(
        f"""# Remote UI 真实交互回归

该套件不调用 Print Mode。每条任务都会启动真实 Remote Server，通过浏览器同协议的
WebSocket 发送请求，消费 Agent/Tool/Durable 事件，再由独立进程重跑测试。

| Task | Result | Durable | Traces | Independent test | Duration |
| --- | ---: | ---: | ---: | ---: | ---: |
{rows}

- 交互任务：{summary['passed']}/{summary['total']} 通过
- Patch 正确：{summary['patchCorrect']}/{summary['total']}
- Durable 生命周期完整：{summary['lifecycleOk']}/{summary['total']}
- 生成 Trace：{summary['withTrace']}/{summary['total']}

真实模型结果受模型版本和网络状态影响，因此默认不作为每次 PR 的强制 CI 门禁。
""", encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--limit", type=int, default=len(TASKS))
    parser.add_argument("--timeout", type=int, default=420)
    args = parser.parse_args()

    jar = ROOT / "build" / "libs" / "codeflow.jar"
    if not jar.is_file():
        raise RuntimeError("Run ./gradlew shadowJar first")
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    task_by_id = {task.task_id: task for task in TASKS}
    selected = (dataset.get("cases") or [])[:max(1, args.limit)]
    unknown = [case["id"] for case in selected if case["id"] not in task_by_id]
    if unknown:
        raise RuntimeError("Unknown task ids: " + ", ".join(unknown))

    work = ROOT / ".codeflow" / "interactive-regression"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    source_config = find_config(args.config)
    replay_config = work / "config.yaml"
    provider, model = write_config(source_config, replay_config, True)
    task_root = work / "tasks"
    task_root.mkdir()
    rows = []
    for index, case in enumerate(selected, 1):
        row = replay_case(case, task_by_id, task_root, replay_config, jar, args.timeout)
        rows.append(row)
        print(f"[{index}/{len(selected)}] {row['id']}: {'PASS' if row['success'] else 'FAIL'}",
              flush=True)

    summary = {
        "total": len(rows),
        "passed": sum(bool(row["success"]) for row in rows),
        "patchCorrect": sum(bool(row["patchCorrect"]) for row in rows),
        "lifecycleOk": sum(bool(row["lifecycleOk"]) for row in rows),
        "withTrace": sum(int(row["traceCount"]) > 0 for row in rows),
    }
    report = {
        "timestampUtc": datetime.now(UTC).replace(microsecond=0).isoformat(),
        "provider": provider,
        "model": model,
        "transport": "Remote WebSocket",
        "summary": summary,
        "cases": rows,
    }
    write_report(report, args.json, args.markdown)
    print(f"Interactive regression report: {args.markdown.resolve()}")
    return 0 if summary["passed"] == summary["total"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"Interactive regression not run: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
