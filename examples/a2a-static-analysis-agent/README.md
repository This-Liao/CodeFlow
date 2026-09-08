# CodeFlow Python A2A Agent

This example proves cross-language and cross-framework delegation: the Java CodeFlow host discovers an A2A 1.0 Agent Card, submits a durable task, polls its lifecycle, and consumes the Markdown/JSON Artifacts produced by a Python LangGraph workflow.

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate
pip install -e .
set CODEFLOW_ANALYSIS_ROOT=D:\path\to\repository
codeflow-static-agent
```

The Agent Card is published at `http://localhost:8001/.well-known/agent-card.json`.

Every request extracts the incoming W3C `traceparent` header and the background
LangGraph execution remains in the same distributed trace. To export both Java
and Python spans to one collector, set the same endpoint before starting them:

```bash
set OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Without a collector the services still create and propagate trace IDs; the A2A
result reports its `metadata.traceId` so the engineering validation can prove
the Java/Python correlation path.
