# NAV Threat Model — DFD, STRIDE, DPIA, audit log, Datatilsynet

Deep reference for threat modeling of NAV applications (here: a Ktor backend in
`no.nav.budstikka`). Used when SKILL.md escalates: a new data category, a new
integration, a DPIA need, or a structured security review before production.

## DFD first

A data-flow diagram with explicit trust boundaries is required before threat
modeling any new surface or integration. Draw only flows that exist; keep names
aligned with the NAIS manifest and the code. Current flows: `docs/flyt.md`;
zones: `nais/nais-dev.yaml`/`nais-prod.yaml` (outbound-only `accessPolicy`) and
the topics in `nais/topics/`.

## STRIDE — NAV-specific points

STRIDE itself is assumed known. NAV-specific points not covered elsewhere:

- **Spoofing** — the right IDP per audience: ID-porten (citizens), Azure AD
  (employees), Maskinporten (external M2M), TokenX (internal on-behalf-of).
  Validation details: `/auth-overview`.
- **Repudiation** — decision and case history need non-overwritable revisions
  (append-only or event-sourced), so "who did what when" can be reconstructed.
- **Information disclosure** — the largest residual risk for NAV apps; logs,
  error messages, URLs, browser cache and uncontrolled outbound are the typical
  leak paths. `Cache-Control: no-store` on responses with personal data.
- **Denial of service** — rarely the primary threat internally, but
  `resources.limits.memory` must be set in the NAIS manifest; CPU is normally
  set only as `resources.requests.cpu`, not `resources.limits.cpu`.
- **Elevation of privilege** — Azure AD groups for RBAC (`claims.groups`), with
  `allowAllUsers: false`.
- **Elevation of privilege** — kode 6/7 and egen ansatt are dedicated access
  control branches, not assumed to be a rare edge case.
- **Elevation of privilege** — `securityContext` in NAIS (runAsNonRoot,
  readOnlyRootFilesystem, drop ALL capabilities) is not overridden.
- **Elevation of privilege** — admin/support functions require an explicit role,
  have their own audit trail, and are not enabled by default in dev.

## DPIA process (Data Protection Impact Assessment)

A DPIA is required before processing likely to produce high risk for the data
subjects — typically: new sensitive data categories, large scale, systematic
monitoring, combining data sets, new technology (AI/ML affecting decisions), or
a substantial change to existing processing. When in doubt: **ask
Personvernombudet** — omitting a required DPIA is a deviation in its own right.
A DPIA is updated when the processing changes, not written once.

### Who decides

- **The data controller** (behandlingsansvarlig — the line organization, typically the product area/benefit line) is responsible for the DPIA being carried out and documented.
- **Personvernombudet (PVO)**, the data protection officer, advises, must be consulted, and assesses whether the DPIA is sufficient. PVO does not decide *for* the data controller, but negative PVO advice that is not followed must be justified in writing.
- **Datatilsynet** is consulted in advance only if the DPIA shows a high residual risk that cannot be mitigated (GDPR art. 36). The threshold is high — most DPIAs end without prior consultation.
- **Team/developer** contributes the technical content: data flow, storage locations, access model, security measures, retention.

## Audit logging requirements for sensitive personal data

### What must be logged

- **Display**: When a NAV employee views personal data in a case worker interface. Logged on actual display, not on every background API check.
- **Change**: Changes to decisions, registrations, or other personal data that bind the administration.
- **Creation and deletion**: New cases, anonymization, retention deletion.
- **Export/disclosure**: Extracts to external recipients, both automated and ad hoc.
- **Decisions**: `Permit`/`Deny` when access is assessed against personal data with restrictions (kode 6/7, egen ansatt).

Do not log: list views without display of personal details, incidental references (e.g. count aggregations), or plain health check calls.

### Format

**CEF (Common Event Format)** for ArcSight. One action = one line. Example structure:

```
CEF:0|<application>|auditLog|1.0|<operation>|Sporingslogg|<severity>|end=<epoch-ms> duid=<subject-id> suid=<actor-id> request=<path> flexString1Label=Decision flexString1=<Permit|Deny>
```

- **Severity**: `INFO` for standard display/change, `WARN` for sensitive cases (fortrolig/strengt fortrolig, egen ansatt).
- **Subject ID (`duid`)**: The person the data is about (fnr/actor). Use a named
  synthetic test identity in source code examples.
- **Actor ID (`suid`)**: The NAV employee performing the action (UPN/email).

A separate logger configuration (`auditLogger`) with its own appender in `logback.xml` — not mixed with the application log.

### For how long

The retention period for the audit log follows archival legislation and NAV's guidelines — typically **several years** (often at least 5, often longer for actions related to personal data). Retention is not decided at team level; follow NAV's central requirements and agree with Team Auditlogging.

The application's own logs (not audit) have a shorter retention and are not a replacement for the audit log.

### Who may see it

- **Team Auditlogging** manages the audit log pipeline and has system access.
- **Lookups in the audit log** require a legitimate work-related need and documented legal authority. Typical cases: subject access requests from users (GDPR art. 15), supervisory cases, internal investigations, PVO reviews.
- **The team itself** does not freely view its own audit log — requests go via Team Auditlogging or the relevant process owner.
- **The user** has a right of access to logging about themselves through a process (not a self-service API).

## Contact with Datatilsynet

The team does *not* contact Datatilsynet directly — contact always goes via
Personvernombudet and the data controller. On suspicion of a personal data
breach: stop it, preserve evidence before cleanup, notify the security champion,
PVO and line manager immediately, and document what/when/scope/measures using
**NAV's prescribed template/system**. The data controller, supported by PVO,
handles the formal notification. Prior consultation on a high-residual-risk
DPIA (art. 36) is likewise initiated via PVO. Document every breach internally,
including those that end without a notification.

## Further reading

- `../SKILL.md` — PII classification, accessPolicy assessment, escalation to the security champion.
- `gdpr-privacy.md` — NAV-specific PII categorization, retention, data minimization.
- `api-security.md` — Nav-Call-Id, Nav-Consumer-Id, accessPolicy as the primary mechanism.
- `/auth-overview` — JWT validation, TokenX/Azure AD, `pid`/NAVident/`azp` claim, Texas sidecar.
- `/kotlin-ktor` — CallId/MDC, outbound HTTP client, DI wiring.
- [sikkerhet.nav.no](https://sikkerhet.nav.no) — NAV's Golden Path, authoritative security guidance.
