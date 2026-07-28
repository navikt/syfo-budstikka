---
name: nav-security-review
description: "Review security-relevant Ktor and NAIS changes before delivery. Use when work touches personal data or logging, secrets, authentication or JWT, accessPolicy, integrations, or DPIA concerns."
---

# Nav security review

This project skill is deliberately named `/nav-security-review`. Copilot CLI's
built-in `/security-review` command remains available as a separate generic
review and does not replace this repository's Nav-specific checklist.

Run this Nav-specific security check before commit, push, and PR work in this
repository. It is the progressively loaded detailed counterpart to the short,
hard boundaries in `security.instructions.md`. Generic OWASP patterns (SQLi,
XSS, CSRF, injection) are assumed; this skill covers Nav context: PII
classification, `accessPolicy` as a security mechanism, and escalation to a
security champion. For JWT validation, claims, and auth configuration in code,
read `/auth-overview`.

## Delivery-loop connection

Use this skill during `@grillmester` verification and before a PR. Report
findings and evidence in the current `KOKK_RESULT` or PR only when that artifact
is authorized. When review finds a binding decision (a new data category,
chosen auth mechanism, or new external integration), recommend the manual
Grill with docs workflow and wait for the user to select it. Durable
documentation writes require that workflow's separate explicit write
confirmation:

- Security trade-offs → `docs/adr/` (one ADR per decision; do not reopen settled choices).
- Findings and evidence (trivy/zizmor output and exit codes) → `KOKK_RESULT`
  or the PR body only within the current write authority.
- Boundaries and data classification → `docs/context.md`.

## Data and logging

Classify data before implementation. In `syfo`, absence information,
sick-leave certificates, and diagnoses are strictly confidential. Never log fnr,
names, diagnoses, or benefit data in standard logs. Display of personal data to
Nav employees goes to a CEF audit log through a dedicated `auditLogger`, never
the standard log.

Read [references/data-and-logging.md](references/data-and-logging.md) for all
four levels, the `00000000000` rule, the Logback example, and CallId/MDC/CEF details.

## accessPolicy as first-line defence

`accessPolicy` in NAIS manifests under `nais/` is the first line of defence, not
an add-on. NAIS default deny means a missing rule breaks access rather than
opening it. A wrong rule, however, exposes a service.

```yaml
spec:
  accessPolicy:
    inbound:
      rules:
        - application: min-frontend         # explicitly named caller
    outbound:
      rules:
        - application: pdl-api
          namespace: pdl
          cluster: prod-gcp
      external:
        - host: api.ekstern-tjeneste.no     # only when strictly necessary
```

**Critical review considerations:**

- **No open inbound:** `inbound.rules` must be explicit. No rules means no access
  (acceptable for an internal batch/job), but open wildcards or broad rules need justification.
- **Inbound and auth code mirror each other:** Every app in `inbound.rules` must
  be validated in auth code (`azp` against `AZURE_APP_PRE_AUTHORIZED_APPS` in
  the `authenticate("azureAd")` branch). A diff implies dead code or a missing
  network rule.
- **Outbound is a security control, not only routing:** Restricted outbound
  limits blast radius when the app is compromised. Every `external` host needs
  a clear purpose and owner.
- **Cluster and namespace match the environment:** `prod-gcp` versus `dev-gcp`.
  A wrong outbound cluster prevents production operation and is often found late.

## Security champion and escalation

Each team has a security champion, or can escalate to the platform security
function. The team owns that role, not this skill.

**Handle without escalation:**

- Parameterised queries, input validation, and standard OWASP patterns.
- CEF audit logging for personal-data display where the pattern is established.
- Standard inbound/outbound `accessPolicy` configuration.
- Trivy/zizmor findings with known fixes.

**Escalate to the security champion (or `#appsec`):**

- **New data class:** First handling of health data, child-welfare data, or code 6/7.
- **DPIA need:** New processing of personal data or a material change to existing
  processing. Read `references/nav-threat-model.md`.
- **New integration with an external domain:** `outbound.external` to a supplier or third party.
- **Auth-mechanism change:** A switch between Azure AD, TokenX, ID-porten, or
  Maskinporten, or a new RBAC model.
- **Suspected incident:** A leaked secret, unauthorised access, or anomalous use.
  Escalate immediately.
- **Compliance assessment outside established patterns:** Supervisory cases,
  Datatilsynet inquiries, or audit responses.

**Urgency:**

- **Acute (call or ping immediately):** Active incident, exposed secret in Git
  history, or suspected personal-data breach.
- **Same day:** New external production integration, changed auth flow, or new data category.
- **Planned (Slack or issue):** DPIA preparation, architecture review, or threat modelling.

Contact channels are processes, not people: the team's internal security-champion
channel; Nav's `#appsec` for general questions; `#auditlogging-arcsight` for
audit logs; and the platform security function for incidents.

## Automated scans

```bash
# Repository vulnerabilities and secrets
trivy repo .

# HIGH/CRITICAL CVEs in the container image
trivy image <image-name> --severity HIGH,CRITICAL

# GitHub Actions workflows
zizmor .github/workflows/

# Secrets in Git history
git log -p --all -S 'password' -- '*.kt' '*.kts' '*.yaml' | head -100
git log -p --all -S 'secret' -- '*.kt' '*.kts' '*.yaml' | head -100
```

Return evidence (command, output, and exit code) in `KOKK_RESULT`/the PR. Do
not make “looks safe” claims without fresh evidence.

## Secrets

```kotlin
// OK — from environment (NAIS injects through a Console secret)
val dbPassword = System.getenv("DB_PASSWORD")
checkNotNull(dbPassword) { "DB_PASSWORD is missing" }

// Never pass a literal like this as dbPassword
val unsafeLiteral = "<hardcoded credential>"
```

Create secrets in Nais Console and inject them through `envFrom`/`filesFrom`.
Also ensure they do not reach `application.conf`, `gradle.properties`, or the
version catalog. Never copy production secrets locally.

## Checklist (Nav focus)

Read [references/review-checklist.md](references/review-checklist.md) before
delivery. It covers PII, audit, policy, secrets, CallId, data minimisation,
controls, scanning, and escalation.

## References

| Resource | Use |
|---------|-------------|
| [sikkerhet.nav.no](https://sikkerhet.nav.no) | Nav's security Golden Path |
| `security.instructions.md` | Always-on (`applyTo: "**"`) security boundaries; complements this on-demand skill |
| `/auth-overview` | JWT validation, TokenX/Azure AD, `pid`/NAVident/`azp` claims, Texas sidecar |
| `/kotlin-ktor` | CallId/MDC and the StatusPages/ApiError error contract |
| `/flyway-migration` | Migrations that add or change PII columns; assess classification and processing basis |
| [references/data-and-logging.md](references/data-and-logging.md) | PII levels, logging, CallId/MDC, and CEF audit |
| [references/review-checklist.md](references/review-checklist.md) | Complete Nav checklist before delivery |
| `references/nav-threat-model.md` | Deep threat modelling (STRIDE in Nav context), DPIA process, audit-log requirements, and Datatilsynet notification |
| `references/gdpr-privacy.md` | Nav-specific PII classification and links to DPIA, CEF, and retention |
| `references/api-security.md` | Nav signals: Nav-Call-Id, Nav-Consumer-Id, and accessPolicy as the primary mechanism |
