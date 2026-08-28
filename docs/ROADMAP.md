# Roadmap

## Implemented

- A2A 1.0 Agent Card discovery and HTTP+JSON/JSON-RPC client transport
- Cross-language Java host → Python LangGraph static-analysis agent
- Durable task snapshots, event log, checkpoints, artifacts, retries, pause/resume
- Cross-process crash recovery validation with checkpoint reuse and duplicate-call audit
- Real-model two-process crash/resume run with persisted Session history and zero repeated mutations
- Stage-aware Tool Schema and memory budgets
- Deterministic 24-task Context / Deferred Tool ablation benchmark and CI gate
- Trace classification, automatic regression dataset, aggregate metric report
- Optional paid-model Context comparison with isolated read-only fixtures
- 12-task real-model Coding Benchmark with immutable tests and independent verification
- Complete CodeFlow package, class, config-path, and environment-variable naming

## Next

- Optional OpenTelemetry export and W3C Trace Context correlation across owned A2A hops
- A2A streaming (`SendStreamingMessage`) and push-notification callbacks
- Pluggable durable stores (SQLite/PostgreSQL) and distributed leases
- Containerized demo environment and protocol conformance tests
- 30-minute to 2-hour soak tests with controlled fault injection
- Web dashboard for task state, artifacts, trace comparison, and approvals
