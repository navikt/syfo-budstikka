---
description: "Always-on security floor for secrets, personal data, SQL, authentication, logging, and NAIS network access."
applyTo: "**"
---

# Security floor

This service processes sickness-absence data.

- Never commit or hard-code credentials, secrets, tokens, or bearer headers;
  never expose them in logs, errors, tool output, examples, or tests. Follow
  the repository's existing secret-injection patterns.
- Keep national identity numbers, names, health or sickness data, payloads, and
  raw external responses out of standard logs and errors. Use only explicitly
  synthetic personal identifiers in examples and tests.
- Parameterize SQL and validate untrusted input at system boundaries.
- Declare least-privilege `accessPolicy` rules in the same change for required
  new inbound or outbound app and host traffic. Use the existing authentication
  pattern.

Follow the R3/R4 gate in `.github/copilot-instructions.md`. Full detail:
`/security-review` for procedure and evidence, `/auth-overview` for token
validation, and `/nais-manifest` for configuration. For logging, audit, or
correlation, read [B46](../../docs/decisions.md#b46) and
[B58](../../docs/decisions.md#b58).
