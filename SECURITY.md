# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability. Use GitHub's private vulnerability reporting for this repository. Include affected versions, reproduction steps, impact, and any proposed mitigation.

## Security model

- Remote A2A Agent Cards, messages, task statuses, and artifacts are untrusted.
- Credentials belong in environment variables or ignored local configuration files.
- Tool execution is governed by permission modes, hooks, path validation, and the available OS sandbox.
- Durable checkpoints and traces can contain repository metadata and must not be committed.
- Remote payloads are bounded; protocol URLs are restricted to HTTP(S).

Rotate any credential immediately if it was ever committed, printed in CI logs, or included in an agent trace.
