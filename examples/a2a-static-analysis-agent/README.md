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
