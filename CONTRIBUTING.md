# Contributing to CodeFlow

Thanks for helping improve CodeFlow.

## Development setup

1. Install JDK 21 or newer.
2. Fork the repository and create a focused branch.
3. Run `./gradlew test` before opening a pull request.
4. Never commit `.mewcode/config.yaml`, `.codeflow/`, API keys, session transcripts, or repository data used by an agent.

## Pull requests

- Keep protocol and persistence changes backward-compatible where practical.
- Add tests for state transitions, malformed remote input, and failure paths—not only happy paths.
- Update README or `docs/` for user-visible behavior.
- For agent behavior changes, attach an eval report and state the baseline used.
- Treat Agent Cards, A2A messages/artifacts, MCP output, tool output, and issue text as untrusted input.

## Commit style

Use concise imperative subjects, for example `feat(a2a): add HTTP+JSON task polling` or `fix(durable): reject stale task updates`.
