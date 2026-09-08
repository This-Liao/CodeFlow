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
- Shared Agent Runtime for TUI, Remote and Print, including true Remote cancellation
- Native Remote Durable task list and Crash/Resume controls
- Trace, Memory, Skill and Context Policy coverage across all three entry modes
- 12-case Remote WebSocket interactive regression set and Replay Runner
- Lexical, Vector and Hybrid Context Policy ablation
- OpenTelemetry Agent/LLM/Tool spans and Java/Python A2A Trace Correlation
- Optional PostgreSQL durable backend with SKIP LOCKED leases, heartbeat and fencing tokens

## Next

- A2A streaming (`SendStreamingMessage`) and push-notification callbacks
- JDBC connection pooling and scheduled headless PostgreSQL queue consumers
- Cross-process OpenTelemetry Span Links for Crash/Resume
- Containerized demo environment and protocol conformance tests
- 30-minute to 2-hour soak tests with controlled fault injection
- Web dashboard for task state, artifacts, trace comparison, and approvals
