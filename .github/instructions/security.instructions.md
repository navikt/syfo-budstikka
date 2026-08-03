---
description: "Always-on security floor for secrets, personal data, SQL, authentication, logging, and NAIS network access."
applyTo: "**"
---

# Security floor

This service processes sickness-absence data.

- Never commit, hard-code, or expose real credentials, secrets, tokens, or
  bearer headers in logs, errors, command output, examples, or fixtures. Follow
  the repository's existing secret-injection patterns.
- Keep personal data — including national identity numbers, names, and health or
  sickness data — plus raw payloads and raw external responses out of logs and
  errors, regardless of log sink.
- Validate and parse untrusted input at system boundaries.
- Parameterize SQL.
- Declare least-privilege `accessPolicy` rules in the same change for required
  new inbound or outbound app and host traffic.
- Use the repository's established authentication pattern.

Follow the R3/R4 gate in `.github/copilot-instructions.md`. Full detail:
`/security-review` for procedure and evidence, `/auth-overview` for token
validation, and `/nais-manifest` for configuration.
