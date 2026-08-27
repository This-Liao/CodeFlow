# Roadmap

## Implemented

- A2A 1.0 Agent Card discovery and HTTP+JSON/JSON-RPC client transport
- Cross-language Java host → Python LangGraph static-analysis agent
- Durable task snapshots, event log, checkpoints, artifacts, retries, pause/resume
- Cross-process crash recovery validation with checkpoint reuse and duplicate-call audit
- Stage-aware Tool Schema and memory budgets
- Deterministic 24-task Context / Deferred Tool ablation benchmark and CI gate
- Trace classification, automatic regression dataset, aggregate metric report
- Optional paid-model benchmark runner with isolated read-only fixtures
- Complete CodeFlow package, class, config-path, and environment-variable naming

## Next

- A2A streaming (`SendStreamingMessage`) and push-notification callbacks
- OpenTelemetry trace export and trace correlation across A2A hops
- Pluggable durable stores (SQLite/PostgreSQL) and distributed leases
- Containerized demo environment and protocol conformance tests
- 30-minute to 2-hour soak tests with controlled fault injection
- Web dashboard for task state, artifacts, trace comparison, and approvals
