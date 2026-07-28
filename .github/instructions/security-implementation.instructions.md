---
description: "Security implementation guidance for Ktor, SQL, Nais, and deployment changes."
applyTo: "**/*.kt, **/*.kts, **/*.sql, nais/**/*.yaml, nais/**/*.yml, .github/workflows/**/*.yaml, .github/workflows/**/*.yml"
---

# Security implementation detail

- Validate untrusted input at the boundary and parameterize SQL. Do not expose
  internal failures or personal data in API errors or logs.
- Protected endpoints must use the established authentication pattern and
  validate issuer and audience. Do not weaken authorization for convenience.
- Flyway migrations are append-only. Assess rolling deploy compatibility,
  retention, and recovery before moving or deleting data.
- New inbound or outbound traffic needs an explicit least-privilege Nais
  `accessPolicy` in the same change. A new host, identity mechanism, external
  integration, or data category needs security review.
- Record concrete verification. For a Grillmester/Kokk slice, apply the brief's
  R3/R4 Inspector contract. A direct Barista task that discovers one of these
  security red flags stops and routes to Grillmester before implementation.

Use `/auth-overview`, `/nais-manifest`, and `/nav-security-review` for the
repository's detailed controls and checklists.
