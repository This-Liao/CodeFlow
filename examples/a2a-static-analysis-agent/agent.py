"""Small A2A 1.0 HTTP+JSON server backed by a LangGraph workflow."""

from __future__ import annotations

import asyncio
import os
import re
import uuid
from pathlib import Path
from typing import Any, TypedDict

import uvicorn
from fastapi import FastAPI, HTTPException, Request
from langgraph.graph import END, START, StateGraph
from opentelemetry import context, propagate, trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind


class AnalysisState(TypedDict):
    request: str
    root: str
    files: list[str]
    findings: list[dict[str, Any]]
    report: str


def discover_files(state: AnalysisState) -> dict[str, Any]:
    root = Path(state["root"])
    files = [
        str(path.relative_to(root)).replace("\\", "/")
        for path in root.rglob("*.java")
        if ".git" not in path.parts and "build" not in path.parts
    ][:2_000]
    return {"files": files}


def analyze_files(state: AnalysisState) -> dict[str, Any]:
    root = Path(state["root"])
    findings: list[dict[str, Any]] = []
    rules = [
        ("hardcoded-secret", re.compile(r"(?i)(api[_-]?key|password|secret)\s*[=:]\s*[\"'][^\"']{8,}"), "high"),
        ("empty-catch", re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}"), "medium"),
        ("blocking-sleep", re.compile(r"Thread\.sleep\("), "low"),
    ]
    for relative in state["files"]:
        path = root / relative
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line_number, line in enumerate(content.splitlines(), 1):
            for rule_id, pattern, severity in rules:
                if pattern.search(line):
                    findings.append(
                        {
                            "rule": rule_id,
                            "severity": severity,
                            "file": relative,
                            "line": line_number,
                            "preview": line.strip()[:180],
                        }
                    )
    return {"findings": findings[:500]}


def render_report(state: AnalysisState) -> dict[str, Any]:
    by_severity = {"high": 0, "medium": 0, "low": 0}
    for finding in state["findings"]:
        by_severity[finding["severity"]] += 1
    report = (
        "# Static analysis report\n\n"
        f"Scanned **{len(state['files'])}** Java files. "
        f"Found **{len(state['findings'])}** candidate issues "
        f"(high={by_severity['high']}, medium={by_severity['medium']}, low={by_severity['low']}).\n"
    )
    return {"report": report}


builder = StateGraph(AnalysisState)
builder.add_node("discover", discover_files)
builder.add_node("analyze", analyze_files)
builder.add_node("render", render_report)
builder.add_edge(START, "discover")
builder.add_edge("discover", "analyze")
builder.add_edge("analyze", "render")
builder.add_edge("render", END)
GRAPH = builder.compile()


def _trace_endpoint(value: str) -> str:
    endpoint = value.rstrip("/")
    if os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or endpoint.endswith("/v1/traces"):
        return endpoint
    return endpoint + "/v1/traces"


def _configure_telemetry() -> None:
    if os.getenv("OTEL_SDK_DISABLED", "").lower() in {"true", "1", "yes", "on"}:
        return
    provider = TracerProvider(
        resource=Resource.create(
            {"service.name": os.getenv("OTEL_SERVICE_NAME", "codeflow-python-a2a")}
        )
    )
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or os.getenv(
        "OTEL_EXPORTER_OTLP_ENDPOINT"
    )
    if endpoint:
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=_trace_endpoint(endpoint)))
        )
    trace.set_tracer_provider(provider)


_configure_telemetry()
TRACER = trace.get_tracer("com.codeflow.a2a.python")

app = FastAPI(title="CodeFlow A2A Static Analysis Agent", version="1.0.0")
TASKS: dict[str, dict[str, Any]] = {}
RUNNERS: dict[str, asyncio.Task[None]] = {}


@app.middleware("http")
async def trace_a2a_request(request: Request, call_next):  # type: ignore[no-untyped-def]
    parent = propagate.extract(dict(request.headers))
    route = request.url.path
    with TRACER.start_as_current_span(
        f"{request.method} {route}", context=parent, kind=SpanKind.SERVER
    ) as span:
        span.set_attribute("http.request.method", request.method)
        span.set_attribute("url.path", route)
        response = await call_next(request)
        span.set_attribute("http.response.status_code", response.status_code)
        return response


def _base_url(request: Request) -> str:
    configured = os.getenv("A2A_PUBLIC_URL")
    return (configured or str(request.base_url).rstrip("/")) + "/a2a/v1"


@app.get("/.well-known/agent-card.json")
async def agent_card(request: Request) -> dict[str, Any]:
    return {
        "name": "CodeFlow Python Static Analysis Agent",
        "description": "LangGraph-powered repository static analysis exposed through A2A 1.0.",
        "version": "1.0.0",
        "supportedInterfaces": [
            {"url": _base_url(request), "protocolBinding": "HTTP+JSON", "protocolVersion": "1.0"}
        ],
        "capabilities": {"streaming": False, "pushNotifications": False, "stateTransitionHistory": True},
        "defaultInputModes": ["text/plain"],
        "defaultOutputModes": ["text/markdown", "application/json"],
        "skills": [
            {
                "id": "repository-static-analysis",
                "name": "Repository static analysis",
                "description": "Scans Java source for security and reliability risks and returns structured findings.",
                "tags": ["java", "static-analysis", "security", "testing"],
                "examples": ["Analyze this repository and return high-risk findings."],
            }
        ],
    }


async def _run_task(task_id: str, prompt: str, parent_context: context.Context) -> None:
    task = TASKS[task_id]
    with TRACER.start_as_current_span(
        "codeflow.a2a.task.execute", context=parent_context, kind=SpanKind.INTERNAL
    ) as span:
        span.set_attribute("codeflow.a2a.task.id", task_id)
        trace_id = f"{span.get_span_context().trace_id:032x}"
        task["metadata"] = {"traceId": trace_id}
        try:
            root = Path(os.getenv("CODEFLOW_ANALYSIS_ROOT", ".")).resolve()
            task["status"] = {"state": "TASK_STATE_WORKING"}
            result = await GRAPH.ainvoke(
                {"request": prompt, "root": str(root), "files": [], "findings": [], "report": ""}
            )
            task["artifacts"] = [
                {
                    "artifactId": str(uuid.uuid4()),
                    "name": "static-analysis-report",
                    "description": "Human-readable summary and structured findings",
                    "parts": [
                        {"text": result["report"], "mediaType": "text/markdown", "filename": "report.md"},
                        {"data": {"findings": result["findings"], "traceId": trace_id}, "mediaType": "application/json", "filename": "findings.json"},
                    ],
                }
            ]
            task["status"] = {
                "state": "TASK_STATE_COMPLETED",
                "message": {"role": "ROLE_AGENT", "parts": [{"text": "Static analysis completed."}]},
            }
        except asyncio.CancelledError:
            task["status"] = {"state": "TASK_STATE_CANCELED"}
            raise
        except Exception as exc:  # task errors belong in A2A status, not a broken HTTP connection
            span.record_exception(exc)
            task["status"] = {
                "state": "TASK_STATE_FAILED",
                "message": {"role": "ROLE_AGENT", "parts": [{"text": f"Analysis failed: {exc}"}]},
            }


@app.post("/a2a/v1/message:send")
async def send_message(payload: dict[str, Any]) -> dict[str, Any]:
    message = payload.get("message") or {}
    text = "\n".join(str(part.get("text", "")) for part in message.get("parts", []) if "text" in part)
    if not text.strip():
        raise HTTPException(status_code=400, detail="message must contain a text Part")
    task_id, context_id = str(uuid.uuid4()), str(uuid.uuid4())
    task = {
        "id": task_id,
        "contextId": context_id,
        "status": {"state": "TASK_STATE_SUBMITTED"},
        "artifacts": [],
        "history": [message],
    }
    TASKS[task_id] = task
    parent_context = context.get_current()
    RUNNERS[task_id] = asyncio.create_task(_run_task(task_id, text, parent_context))
    return {"task": task}


@app.get("/a2a/v1/tasks/{task_id}")
async def get_task(task_id: str) -> dict[str, Any]:
    task = TASKS.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="TaskNotFoundError")
    return task


@app.get("/a2a/v1/tasks")
async def list_tasks() -> dict[str, Any]:
    return {"tasks": list(TASKS.values())}


@app.post("/a2a/v1/tasks/{task_id}:cancel")
async def cancel_task(task_id: str) -> dict[str, Any]:
    task = TASKS.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="TaskNotFoundError")
    runner = RUNNERS.get(task_id)
    if runner is not None and not runner.done():
        runner.cancel()
        task["status"] = {"state": "TASK_STATE_CANCELED"}
    return task


def main() -> None:
    uvicorn.run("agent:app", host="0.0.0.0", port=int(os.getenv("PORT", "8001")), reload=False)


if __name__ == "__main__":
    main()
