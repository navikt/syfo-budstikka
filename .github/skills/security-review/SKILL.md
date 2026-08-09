---
name: security-review
description: "Security review of a Ktor backend in no.nav.budstikka: PII/FNR/health data in logs, secrets, CEF audit log, accessPolicy in the NAIS manifest, JWT validation (TokenX/Azure AD), external integrations, DPIA and escalation to the security champion. Used before commit/push/PR with security relevance, or when someone says 'security review' / 'sikkerhetsgjennomgang', or /security-review."
---

# Security review — NAV context

NAV-specific security check before commit, push and PR in this repository. Generic OWASP patterns (SQLi, XSS, CSRF, injection) are assumed known — this document focuses on the NAV context: PII classification, accessPolicy as a security mechanism, and escalation to the security champion. For JWT validation, claims and auth setup in the code, see `/auth-overview`.

## Flow coupling

This skill is typically used in the calling workflow's verify step, and before a PR. When the review uncovers lasting value:

- A candidate that passes the ADR gate → recommend the documented route and wait
  for the user's choice before `/domain-modeling` writes.
- Return review findings and deterministic tool evidence (trivy/zizmor output,
  exit codes) to the active task. Write them to a task-local `.grill/` file only
  when the calling workflow has explicitly chosen that.
- Maintained data-handling frames that follow from an approved change → the
  relevant topic document. New domain concepts are candidates for the documented
  route; wait for the user's choice before the glossary is updated.

## PII classification in NAV

NAV processes personal data at four protection levels. Incorrect classification is the most common root cause of serious deviations.

| Level | Typical data | Handling |
|------|--------------|------------|
| **Strengt fortrolig** (strictly confidential) | Health data, diagnoses, sykmeldinger, people exposed to violence/kode 6, child welfare data | Encryption at rest and in transit, strict access control, CEF audit log on display (WARN), dedicated DPIA |
| **Fortrolig** (confidential) | National identity number (`fnr`), D-number, kode 7, sensitive benefits data | Never in standard logs, CEF audit log on display, access control per case/user |
| **Intern** (internal) | Name, address, phone, email, non-sensitive benefit status | Data minimization, access on a need-to-know basis, retention documented |
| **Åpen** (open) | Public statistics, anonymized aggregates | Normal access; verify that the anonymization withstands linkage attacks |

The `syfo` domain handles sykefravær and sykmeldinger — the fact that "a user is sykmeldt" is **strengt fortrolig** (implicit health information). Treat `fnr`, sykmeldinger and diagnoses accordingly.

**Placeholder in code and documentation**: Never use a real `fnr`. Use a clearly
named synthetic test identity from the test fixture or Skatteetaten's official
test series, explicitly marked as synthetic. See
`references/nav-threat-model.md` for the DPIA process and audit requirements.

### PII in logs

This repository logs via Logback (`src/main/resources/logback.xml`). Use structured fields, never PII in the message text:

```kotlin
// OK — correlation ID and tema, no PII
log.info("Processing case", kv("callId", MDC.get("callId")), kv("tema", sak.tema))

// Never — FNR, name, diagnosis or benefits data in the standard log
log.info("Processing case for ${bruker.fnr}")
```

`Nav-Call-Id` is propagated on outbound calls that carry it (see `/kotlin-ktor`); worker and consumer log lines correlate through `MdcKeys` + `MDCContext`. Note that `callIdMdc` is not installed, so an incoming callId does not reach MDC by itself. Display of personal data to NAV employees must be logged in **CEF format** to the audit log (a dedicated `auditLogger`, not the standard log). See `references/nav-threat-model.md` for the format and what to log when.

## accessPolicy as first-line defense

`accessPolicy` in the NAIS manifest (`.nais/` yaml or equivalent) is the first line of defense — not an additional mechanism. Default deny on the NAIS platform means that a forgotten rule = broken access, not open access. But a wrong rule = an exposed service.

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

**Critical considerations during review:**

- **No open inbound**: `inbound.rules` must be an explicit list. Absence of rules = no access (fine for internal batch/job), but open wildcards or many general rules require justification.
- **Inbound and auth code mirror each other**: Every app in `inbound.rules` must be validated in the auth code (`azp` check against `AZURE_APP_PRE_AUTHORIZED_APPS` in the `authenticate("azureAd")` branch). Diff the discrepancies — either dead code or a missing network rule.
- **Outbound is a security measure, not just routing**: Restricted outbound = limited blast radius if the app is compromised. Outbound `external` must have a clear purpose and owner.
- **Cluster/namespace match the environment**: `prod-gcp` vs `dev-gcp` — the wrong cluster in outbound = the service does not work in prod, but this is often discovered late.

## Security champion role and escalation

Every team has a security champion (or can escalate to the platform's security function). This role is owned by the team, not by the `security-review` skill.

**When the skill handles it (no escalation):**

- Parameterized queries, input validation, standard OWASP patterns.
- CEF audit log on display of personal data. This repository has no audit logger today (`grep -rn 'auditLogger\|CEF' src/` is empty) because it is a worker/consumer with no endpoint that shows personal data to a NAV employee. Adding such an endpoint requires adding CEF audit logging in the same change.
- accessPolicy setup for standard inbound/outbound.
- Trivy/zizmor findings with known fixes.

**When you escalate to the security champion (or `#appsec`):**

- **A new class of data**: The first time the team processes health data, child welfare data or kode 6/7.
- **DPIA need**: New processing involving personal data, or a substantial change to existing processing. See `references/nav-threat-model.md`.
- **New integration with an external domain**: `outbound.external` towards a vendor/third party.
- **Change of authentication mechanism**: Switching between Azure AD/TokenX/ID-porten/Maskinporten, or a new RBAC model.
- **Suspected incident**: Leaked secret, unauthorized access, anomalous usage pattern — do not wait, escalate immediately.
- **Compliance assessment outside the standard pattern**: Supervisory cases, Datatilsynet enquiries, responses to audits.

**Urgency:**

- **Acute (call/ping immediately)**: Active incident, exposed secret in git history, suspected personal data breach.
- **Same day**: New external integration in prod, changed authentication flow, new data categories.
- **Planned (Slack/issue)**: DPIA preparation, architecture review, threat modeling.

Contact channels (process, not people): The team's internal security champion channel; NAV's `#appsec` for general questions; `#auditlogging-arcsight` for the audit log; the platform's security function for incidents.

## Automated scans

```bash
# Vulnerabilities and secrets in the repository
trivy repo .

# HIGH/CRITICAL CVEs in the container image
trivy image <image-name> --severity HIGH,CRITICAL

# GitHub Actions workflows
zizmor .github/workflows/

# Secrets in git history
git log -p --all -S 'password' -- '*.kt' '*.kts' '*.yaml' | head -100
git log -p --all -S 'secret' -- '*.kt' '*.kts' '*.yaml' | head -100
```

Return evidence (command + output + exit code) to the calling workflow — no
"looks safe" claims without fresh evidence.

## Secrets

```kotlin
// OK — from the environment (NAIS injects via a Console secret)
val dbPassword = System.getenv("DB_PASSWORD")
    ?: error("DB_PASSWORD missing")

// Never — hardcoded
val dbPassword = "supersecret123"
```

Secrets are created in NAIS Console and injected via `envFrom`/`filesFrom`. Also check that they do not end up in `application.conf`, `gradle.properties` or the version catalog. Never copy prod secrets locally.

## Checklist (NAV focus)

- [ ] PII classification is settled for all data the service processes (strengt fortrolig/fortrolig/intern/åpen) and maintained in the relevant topic document
- [ ] No FNR, names, health data or sensitive benefits data in standard logs
- [ ] CEF audit log covers display of personal data to NAV employees
- [ ] `accessPolicy.inbound` is explicit and mirrors the auth code's validation
- [ ] `accessPolicy.outbound` limited to the necessary services/hosts with correct cluster/namespace
- [ ] Secrets only from NAIS Console, no hardcoded values or prod secrets locally
- [ ] `Nav-Call-Id` is set explicitly on outbound calls that carry it (`callIdMdc` is not installed — do not assume MDC correlation)
- [ ] Legal basis for processing, retention and deletion are documented for personal data
- [ ] Parameterized queries, input validated, access control checks ownership (not just a valid token)
- [ ] `trivy repo .` with no HIGH/CRITICAL, `zizmor` OK, no committed secrets
- [ ] Escalation to the security champion has been considered for new data categories, integrations or auth changes
- [ ] DPIA need assessed (see `references/nav-threat-model.md`) before new processing of personal data

## References

| Resource | Use |
|---------|-------------|
| [sikkerhet.nav.no](https://sikkerhet.nav.no) | NAV's Golden Path for security |
| `security.instructions.md` | Always-on (`applyTo: "**"`) security boundaries — complements this on-demand skill |
| `/auth-overview` | JWT validation, TokenX/Azure AD, `pid`/NAVident/`azp` claim, Texas sidecar |
| `/kotlin-ktor` | CallId/MDC, outbound HTTP client, DI wiring |
| `/postgresql-review` | Migrations that add/change PII columns — assess classification and legal basis for processing |
| `references/nav-threat-model.md` | Deep threat modeling (STRIDE in a NAV context), DPIA process, audit logging requirements, Datatilsynet notification |
| `references/gdpr-privacy.md` | NAV-specific PII categorization and pointers to DPIA/CEF/retention |
| `references/api-security.md` | NAV signal: Nav-Call-Id, Nav-Consumer-Id, accessPolicy as the primary mechanism |
