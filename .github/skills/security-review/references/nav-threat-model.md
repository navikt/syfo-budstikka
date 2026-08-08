# NAV Threat Model — STRIDE, DFD, threat table, DPIA, audit log, Datatilsynet

Deep reference for threat modeling of NAV applications (here: a Ktor backend in
`no.nav.budstikka`). Used when SKILL.md escalates: a new data category, a new
integration, a DPIA need, or a structured security review before going to
production. Maintain the model in the relevant topic document when it is part of
the approved change, and return verification evidence to the active task. When
the modeling uncovers a lasting choice that passes the ADR gate, recommend the
documented route and wait for the user's choice before `/domain-modeling`
records it.

## DFD first

Start with a simple DFD before you consider STRIDE. The goal is to make data flows and trust boundaries visible, not to draw a perfect architecture diagram.

### Minimum in the DFD

- at least one external actor or consumer
- the processes that actually handle data (Ktor routes/modules)
- data stores and message streams (PostgreSQL, Kafka)
- every transition between ingress, app, Kafka, database and external services
- markings for where personal data enters, is stored and is sent out

### Simple text template

```text
[Citizen or case worker]
    |
    | HTTPS / token (TokenX / Azure AD)
    v
== Ingress / Wonderwall ==
    |
    v
(Ktor backend, no.nav.budstikka)
    | \
    |  \---> {Kafka topic}
    |
    +-----> {PostgreSQL}
    |
    +-----> [External service / PDL]
```

### DFD checklist

- Draw only the flows that exist in the solution today
- Distinguish between human actor, internal service and external dependency
- Mark every trust boundary explicitly
- Note where auth is validated and where authorization is actually decided
- Keep the same names in the DFD, the NAIS manifest and the code where possible

## Threat table — minimum format

Once the DFD is ready, document the threats in a table. The minimum requirement is one row per important flow or component, with measures and residual risk.

| Component or flow | Data/asset | STRIDE | Threat | Existing protection | Measure or follow-up | Residual risk | Owner |
|----------------------|--------------|--------|---------|-------------------|-------------------------|------------|------|
| Frontend → backend via TokenX | Personal data, tokens | Spoofing / Information Disclosure | Wrong token type or overly broad response | Token validation, Wonderwall, DTOs | Verify `aud`/`iss`, limit the fields in the response | Low/medium | The team |
| Backend → PostgreSQL | Case data, sykmeldinger | Tampering / Denial of Service | Invalid input or expensive queries affect integrity/availability | Parameterized queries, constraints, pool | Add pagination, time limits, query review | Medium | The team |
| Case worker → person view | Fortrolig or strengt fortrolig data | Repudiation / Elevation of Privilege | Unauthorized lookup or missing traceability | RBAC, audit log, access control | Verify kode 6/7 and egen ansatt in a dedicated branch | Medium | Team + process owner |

Use short, concrete descriptions. Drop generic "could be attacked" claims that come without a measure or an owner.

## STRIDE adapted to the NAV context

STRIDE is used here as a checklist, not as a generic academic exercise. For each category: which *NAV-concrete* attack surfaces and measures apply.

### Spoofing (identity forgery)

**NAV context:** An attacker poses as a user, as a NAV employee, or as another service in the cluster.

- [ ] User authentication via the right IDP: ID-porten for citizens, Azure AD for employees, Maskinporten for external M2M, TokenX for internal service-to-service on behalf of a user.
- [ ] Token validation validates `iss`, `aud`, `exp`, `nbf` and the signature against the IDP's JWKS (`token-validation-ktor-v3`/`tokenValidationSupport`, see `/auth-overview`).
- [ ] For Azure AD M2M: the `azp` claim is validated against `AZURE_APP_PRE_AUTHORIZED_APPS`. Without this check, any app in the tenant can call the service.
- [ ] The call ID (`Nav-Call-Id`) is propagated, but is *never* used as a basis for authorization.
- [ ] For impersonation/on-behalf-of: Verify that the original user is the correct subject user in `sub`/`pid`.

### Tampering (unauthorized modification)

**NAV context:** Modification of case/decision/benefits data along the way, either in transit or in databases/queues.

- [ ] TLS on all traffic (NAIS default). No plain HTTP to internal or external services.
- [ ] Input validation at the boundary (DTO + explicit validation in the route handler), not only in the business layer.
- [ ] Integrity check when receiving external messages (e.g. signed Kafka messages where the contract requires it).
- [ ] Idempotency keys on writing endpoints that can be retried.
- [ ] Databases: FKs and constraints enforce the invariants (defined in Flyway migrations); the app does not "assume" that data is valid.

### Repudiation (denial of action)

**NAV context:** A user or employee can later claim that an action was not performed, or that personal data was never displayed.

- [ ] CEF audit log when NAV employees view personal data (not on every API check — on actual display).
- [ ] One action = one audit log line. No losses in silent failures.
- [ ] The audit log contains: timestamp, action (`audit:read/update/create/delete`), subject ID (fnr/actor), performer (employee UPN/email), request path, decision (`Permit`/`Deny`).
- [ ] The audit log is written to a dedicated logger (`auditLogger`) with its own appender in `logback.xml` — not mixed into the application log.
- [ ] Decision and case history have non-overwritable revisions (append-only or event-sourced), so that "who did what when" can be reconstructed.

See the section [Audit logging requirements](#audit-logging-requirements-for-sensitive-personal-data) for details.

### Information Disclosure (information leakage)

**NAV context:** The largest residual risk for NAV apps. Leakage can happen via logs, error messages, browser cache, URLs, or uncontrolled outbound.

- [ ] No PII (fnr, name, diagnosis, sykmelding, benefits data) in the standard log. See SKILL.md for classification.
- [ ] Error messages to the client are generic; details are logged with a correlation ID, not returned (Ktor `StatusPages` + a shared error contract, see `/kotlin-ktor`).
- [ ] Personal data never in the URL path or query (it ends up in access logs, browser history, referer headers). Use the body or a header.
- [ ] `accessPolicy.outbound` limits where the app can send data — limit exfiltration surfaces.
- [ ] Cache-Control: `no-store` on responses containing personal data.
- [ ] Secrets never in logs, error objects, exception messages or stack traces that are exposed.
- [ ] Anonymized aggregates are assessed against linkage attacks (k-anonymity for small cohorts).

### Denial of Service

**NAV context:** Rarely the primary threat for internal services (NAIS has resource limits), but publicly exposed services (applications/forms) must be assessed.

- [ ] `resources.limits.memory` is set in the NAIS manifest. CPU is normally only set as `resources.requests.cpu`, not as `resources.limits.cpu`.
- [ ] Rate limiting on publicly exposed and expensive endpoints.
- [ ] Limits on request size and payload depth (e.g. JSON nesting) where external input is accepted.
- [ ] File upload has both a size limit and a count limit per user/case.
- [ ] Expensive database queries have pagination and a time limit.

### Elevation of Privilege

**NAV context:** Escalation from citizen to case worker, from case worker to superuser, or from one case worker to another's area of responsibility.

- [ ] Access control checks *ownership/affiliation*, not just authentication (IDOR test).
- [ ] Azure AD groups are used for RBAC (`claims.groups`) — `allowAllUsers: false`.
- [ ] Kode 6/7 and egen ansatt are handled as dedicated access control branches, not assumed to be a rare edge case.
- [ ] `securityContext` in NAIS (runAsNonRoot, readOnlyRootFilesystem, drop ALL capabilities) is not overridden.
- [ ] Admin/support functions require an explicit role, have their own audit trail, and are not enabled by default in the dev environment (avoid "forgotten" backdoors).

## DPIA process (Data Protection Impact Assessment)

### When a DPIA is required

A DPIA is required before starting processing that is likely to result in a high risk to the data subject's rights (GDPR art. 35). In a NAV context, a DPIA is typically triggered by:

- **New processing of sensitive personal data**: Health data, sykmeldinger, child welfare data, biometrics, genetic data.
- **Large scale**: Processing that covers a significant share of NAV's user base.
- **Systematic monitoring**: Usage analytics, automated decisions, profiling.
- **Combining data sets**: Combining sources that were each limited on their own (e.g. benefits + health data + tax).
- **New technology**: AI/ML models that affect decisions about individuals, new storage/processing paradigms.
- **Substantial change**: Existing processing changes purpose, data category, source, or recipient group.

When in doubt: **ask Personvernombudet** (the data protection officer). Omitting a required DPIA is a deviation in its own right.

### Who decides

- **The data controller** (behandlingsansvarlig — the line organization, typically the product area/benefit line) is responsible for the DPIA being carried out and documented.
- **Personvernombudet (PVO)**, the data protection officer, advises, must be consulted, and assesses whether the DPIA is sufficient. PVO does not decide *for* the data controller, but negative PVO advice that is not followed must be justified in writing.
- **Datatilsynet** is consulted in advance only if the DPIA shows a high residual risk that cannot be mitigated (GDPR art. 36). The threshold is high — most DPIAs end without prior consultation.
- **Team/developer** contributes the technical content: data flow, storage locations, access model, security measures, retention.

### DPIA content (minimum)

1. A systematic description of the processing and its purpose.
2. An assessment of necessity and proportionality relative to the purpose.
3. An assessment of the risk to the data subjects' rights and freedoms.
4. Measures to reduce risk (technical and organizational).
5. Consultation with PVO (and, where relevant, affected groups).

A DPIA is not a one-off document — it is updated when the processing changes.

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

Datatilsynet is the supervisory authority for data protection legislation. The team does *not* contact Datatilsynet directly — it always goes via Personvernombudet and the data controller.

### When to notify — breach notification

A **personal data breach** (GDPR art. 33) must be reported to Datatilsynet without undue delay and no later than **within 72 hours** after the data controller has become aware of it, unless the breach is unlikely to result in a risk to the data subject's rights and freedoms.

Examples that typically trigger a notification duty:

- Unauthorized access to personal data (e.g. a case worker sees an fnr they should not have had).
- Disclosure to the wrong recipient (email, API response, printout).
- Loss of availability (deletion, ransomware, unrecoverable failure).
- Leaked authentication material that grants access to personal data.
- Publication of personal data in a public source code repository or an open log.

The team's job on suspicion:

1. **Stop the breach** (revoke access, rotate the secret, remove data if possible).
2. **Preserve evidence** (logs, commit history, affected records) before cleanup.
3. **Notify immediately** internally: security champion, PVO, line manager.
4. **Document**: what, when, scope, measures, parties notified. Use NAV's prescribed template/system.

The data controller, supported by PVO, assesses and sends the formal notification to Datatilsynet within 72 hours.

### When to notify — prior consultation on a DPIA

If the DPIA shows that the processing would result in a high residual risk even after mitigation, **prior consultation** with Datatilsynet must take place before the processing starts (GDPR art. 36). This is a separate process, not a breach notification, and it is initiated via PVO.

### What to notify

A breach notification to Datatilsynet typically contains:

- The nature of the breach (category: access, disclosure, loss).
- The categories and approximate number of data subjects affected.
- The categories and approximate number of records affected.
- The likely consequences for the data subjects.
- Measures taken or proposed to handle the breach and reduce its adverse effects.
- A point of contact (PVO) for follow-up.

When the breach is likely to result in a **high risk** to the data subject's rights, **the data subject must also be notified** (GDPR art. 34) — this is a separate process owned by the data controller.

### Documentation regardless

All breaches must be documented internally — including those that do not trigger notification to Datatilsynet. The documentation is the basis for supervision, learning and later assessments.

## Further reading

- `../SKILL.md` — PII classification, accessPolicy assessment, escalation to the security champion.
- `gdpr-privacy.md` — NAV-specific PII categorization, retention, data minimization.
- `api-security.md` — Nav-Call-Id, Nav-Consumer-Id, accessPolicy as the primary mechanism.
- `/auth-overview` — JWT validation, TokenX/Azure AD, `pid`/NAVident/`azp` claim, Texas sidecar.
- `/kotlin-ktor` — StatusPages/ApiError error contract, CallId/MDC.
- [sikkerhet.nav.no](https://sikkerhet.nav.no) — NAV's Golden Path, authoritative security guidance.
