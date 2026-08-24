---
description: "Always-on security floor for secrets, personal data, SQL, authentication, logging, NAIS network access, and container base images."
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
  new inbound or outbound app and host traffic. In a merge or rebase conflict,
  NAIS-manifest `accessPolicy`, env and scaling entries merge as sets: keep the
  entries from both sides. Resolving by picking one side silently drops an
  access rule and takes down production traffic.
- Use the repository's established authentication pattern.
- Build container images on Chainguard — the only base image permitted in Nav
  (distroless is tolerated; `ubuntu`, `debian` and `openjdk` are not). Pull it
  through Nav's registry path
  `europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/<image>:<tag>`, and
  match the base-image major version to the toolchain major version — Chainguard
  does not backport. Never pin a digest by hand; let
  [digestabot](https://github.com/navikt/digestabot) raise the pinning pull
  requests. Reference:
  <https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages>.

Follow the R3/R4 gate in `.github/copilot-instructions.md`.
