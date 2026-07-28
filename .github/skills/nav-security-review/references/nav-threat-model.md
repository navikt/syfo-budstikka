# Nav threat model — STRIDE, DFD, threat table, DPIA, audit log, and Datatilsynet

Deep reference for threat-modelling Nav applications (here, the Ktor backend in
`no.nav.budstikka`). Use it when SKILL.md escalates for a new data category, new
integration, DPIA need, or structured security review before production release.
Record results in `docs/adr/` (decisions) and `KOKK_RESULT`/the PR (evidence).

## Start with a DFD

Create a simple DFD before assessing STRIDE. Its purpose is to expose data flow
and trust boundaries, not to draw a perfect architecture diagram.

### Minimum DFD content

- At least one external actor or consumer.
- Processes that actually handle data (Ktor routes/modules).
- Data stores and message streams (PostgreSQL, Kafka).
- Every transition between ingress, app, Kafka, database, and external services.
- Where personal data enters, is stored, and leaves.

### Simple text template

```text
[Citizen or caseworker]
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

- Draw only flows that exist in the current solution.
- Distinguish human actors, internal services, and external dependencies.
- Mark every trust boundary explicitly.
- Note where auth is validated and where authorisation is actually decided.
- Use the same names in the DFD, NAIS manifest, and code where possible.

## Threat table — minimum format

Once the DFD is ready, document threats in a table. The minimum is one row per
important flow or component with mitigation and residual risk.

| Component or flow | Data/asset | STRIDE | Threat | Existing protection | Mitigation or follow-up | Residual risk | Owner |
|-------------------|------------|--------|--------|---------------------|-------------------------|---------------|-------|
| Frontend → backend through TokenX | Personal data, tokens | Spoofing / Information Disclosure | Wrong token type or overly broad response | Token validation, Wonderwall, DTOs | Verify `aud`/`iss`, limit response fields | Low/medium | Team |
| Backend → PostgreSQL | Case data, sick-leave certificates | Tampering / Denial of Service | Invalid input or costly queries affect integrity/availability | Parameterised queries, constraints, pool | Add pagination, time limits, query review | Medium | Team |
| Caseworker → personal-data display | Confidential or strictly confidential data | Repudiation / Elevation of Privilege | Unauthorised lookup or missing traceability | RBAC, audit logging, access control | Verify code 6/7 and own-employee handling in separate branches | Medium | Team + process owner |

Use short, concrete descriptions. Omit generic “could be attacked” claims with
no mitigation or owner.

## STRIDE adapted to Nav context

Use STRIDE as a checklist, not a generic academic exercise. For each category,
identify concrete Nav attack surfaces and controls.

### Spoofing (identity impersonation)

**Nav context:** An attacker pretends to be a user, Nav employee, or another
service in the cluster.

- [ ] Authenticate users through the right IDP: ID-porten for citizens, Azure AD
  for employees, Maskinporten for external M2M, and TokenX for internal
  service-to-service on behalf of a user.
- [ ] Token validation verifies `iss`, `aud`, `exp`, `nbf`, and signature against
  the IDP JWKS (`token-validation-ktor-v3`/`tokenValidationSupport`; see `/auth-overview`).
- [ ] For Azure AD M2M, validate `azp` against `AZURE_APP_PRE_AUTHORIZED_APPS`.
  Without it, any application in the tenant can call the service.
- [ ] Propagate Call-ID (`Nav-Call-Id`), but **never** use it as authorisation.
- [ ] For impersonation/on-behalf-of, verify the original user is the correct
  subject in `sub`/`pid`.

### Tampering

**Nav context:** Case, decision, or benefit data is changed in transit, a database,
or a queue.

- [ ] Use TLS for all traffic (NAIS default). No plain HTTP to internal or external services.
- [ ] Validate input at the boundary (DTO plus explicit route-handler validation),
  not only in business logic.
- [ ] Verify integrity when receiving external messages, for example signed Kafka
  messages when the contract requires them.
- [ ] Use idempotency keys on write endpoints that may be retried.
- [ ] Make databases enforce invariants with foreign keys and constraints defined
  in Flyway migrations; never assume data is valid.

### Repudiation

**Nav context:** A user or employee can later claim an action did not happen, or
that personal-data display did not happen.

- [ ] Write a CEF audit log when Nav employees display personal data (at actual
  display, not every API check).
- [ ] Write one audit-log line per action. Do not lose events in silent failures.
- [ ] Include timestamp, action (`audit:read/update/create/delete`), subject ID
  (fnr/actor), performer (employee UPN/email), request path, and decision
  (`Permit`/`Deny`).
- [ ] Write audit data through a separate `auditLogger` and appender in
  `logback.xml`, never the application log.
- [ ] Keep decision and case history non-overwritable (append-only or event
  sourced) so “who did what when” can be reconstructed.

Read [Audit-log requirements](#audit-log-requirements-for-sensitive-personal-data)
for details.

### Information disclosure

**Nav context:** This is the largest residual risk for Nav applications. Leakage
can occur through logs, errors, browser caches, URLs, or unrestricted outbound.

- [ ] Do not put PII (fnr, name, diagnosis, sick-leave certificate, benefit data)
  in standard logs. Read `data-and-logging.md` for classification.
- [ ] Keep client errors generic; log details with correlation ID rather than
  returning them (Ktor `StatusPages` plus the common error contract; see `/kotlin-ktor`).
- [ ] Never put personal data in URL path or query, which reach access logs,
  browser history, and referer headers. Use a body or header.
- [ ] Limit destinations through `accessPolicy.outbound` to reduce exfiltration surfaces.
- [ ] Set `Cache-Control: no-store` on responses containing personal data.
- [ ] Keep secrets out of logs, error objects, exception messages, and exposed stack traces.
- [ ] Assess anonymised aggregates for linkage attacks (k-anonymity for small cohorts).

### Denial of Service

**Nav context:** Rarely the primary threat for internal services (NAIS has
resource limits), but assess publicly exposed services such as applications.

- [ ] Set `resources.limits.memory` in the NAIS manifest. CPU normally appears
  only as `resources.requests.cpu`, not `resources.limits.cpu`.
- [ ] Rate-limit publicly exposed and costly endpoints.
- [ ] Limit request size and payload depth, for example JSON nesting, for external input.
- [ ] Limit both file size and count per user/case for uploads.
- [ ] Give expensive database queries pagination and time limits.

### Elevation of Privilege

**Nav context:** Escalation from citizen to caseworker, caseworker to superuser,
or one caseworker to another's case area.

- [ ] Make access control check **ownership/association**, not only authentication
  (IDOR test).
- [ ] Use Azure AD groups for RBAC (`claims.groups`) with `allowAllUsers: false`.
- [ ] Handle code 6/7 and own-employee cases as separate access-control branches,
  not rare edge cases.
- [ ] Do not override NAIS `securityContext` (`runAsNonRoot`,
  `readOnlyRootFilesystem`, drop ALL capabilities).
- [ ] Require an explicit role and separate audit for admin/support functions;
  do not enable them by default in development (avoid forgotten backdoors).

## DPIA process (Data Protection Impact Assessment)

### When a DPIA is required

A DPIA is required before processing likely to create a high risk to data-subject
rights (GDPR Article 35). In Nav context, typical triggers are:

- **New processing of sensitive personal data:** Health data, sick-leave
  certificates, child-welfare data, biometrics, or genetic data.
- **Large scale:** Processing that covers a significant share of Nav's user base.
- **Systematic monitoring:** Usage analysis, automated decisions, or profiling.
- **Dataset combination:** Combining sources that were individually bounded, for
  example benefit, health, and tax data.
- **New technology:** AI/ML models that affect individual decisions, or new
  storage or processing paradigms.
- **Material change:** Existing processing changes purpose, data category,
  source, or recipient group.

When in doubt, **ask the Data Protection Officer (PVO)**. Omitting a required
DPIA is an incident in itself.

### Who decides

- **Controller** (the line organisation, normally a product or benefits area)
  is responsible for completing and documenting the DPIA.
- **Data Protection Officer (PVO)** advises, must be consulted, and assesses
  whether the DPIA is sufficient. PVO does not decide for the controller; a
  negative PVO recommendation that is not followed requires written rationale.
- **Datatilsynet** is consulted in advance only if the DPIA shows high residual
  risk that cannot be mitigated (GDPR Article 36). The threshold is high; most
  DPIAs need no prior consultation.
- **Team/developer** contributes technical content: data flow, storage locations,
  access model, controls, and retention.

### Minimum DPIA content

1. Systematic description of processing and purpose.
2. Assessment of necessity and proportionality for the purpose.
3. Assessment of risk to data-subject rights and freedoms.
4. Measures to reduce risk, both technical and organisational.
5. Consultation with PVO and, where relevant, affected groups.

A DPIA is not a one-time document; update it when processing changes.

## Audit-log requirements for sensitive personal data

### What to log

- **Display:** When a Nav employee sees personal data in a caseworker surface.
  Log at actual display, not every background API check.
- **Change:** Changes to decisions, registrations, or other personal data that
  bind administration.
- **Creation and deletion:** New cases, anonymisation, retention deletion.
- **Export/disclosure:** Extraction to external recipients, automated or ad hoc.
- **Decisions:** `Permit`/`Deny` when access to restricted personal data is
  evaluated (code 6/7 or own employee).

Do not log list views without personal-detail display, incidental references
(for example counts), or pure health-check calls.

### Format

Use **CEF (Common Event Format)** for ArcSight. One action equals one line:

```
CEF:0|<application>|auditLog|1.0|<operation>|Sporingslogg|<severity>|end=<epoch-ms> duid=<subject-id> suid=<actor-id> request=<path> flexString1Label=Decision flexString1=<Permit|Deny>
```

- **Severity:** `INFO` for normal display/change; `WARN` for sensitive cases
  (confidential/strictly confidential, own employee).
- **Subject ID (`duid`):** Person whose data is involved (fnr/actor). Use
  `00000000000` or clearly synthetic test data in source-code examples.
- **Actor ID (`suid`):** Nav employee performing the action (UPN/email).

Configure a separate `auditLogger` with its own appender in `logback.xml`; never
mix it with the application log.

### Retention

Audit-log retention follows archive law and Nav guidance — typically **several
years** (often at least five, often longer for personal-data actions). A team
does not decide retention itself; follow Nav's central requirements and agree
with Team Auditlogging.

Application logs (not audit logs) have shorter retention and are not a substitute.

### Who may view it

- **Team Auditlogging** operates the audit-log pipeline and has system access.
- **Audit-log lookup** requires official need and documented legal basis. Typical
  cases: a user's access request (GDPR Article 15), supervision, internal
  investigation, or PVO review.
- **The team itself** does not freely access its own audit log; request it through
  Team Auditlogging or the relevant process owner.
- **The user** has a right of access to logging about themselves through the
  established process, not a self-service API.

## Contact with Datatilsynet

Datatilsynet is Norway's supervisory authority for privacy law. The team does
**not** contact it directly; always proceed through the Data Protection Officer
and the controller.

### When to notify — incident report

A **personal-data breach** (GDPR Article 33) must be reported to Datatilsynet
without undue delay and no later than **72 hours** after the controller becomes
aware of it, unless the breach is unlikely to create a risk to data-subject
rights and freedoms.

Examples that typically trigger notification:

- Unauthorised access to personal data, for example a caseworker viewing an fnr
  they should not have seen.
- Disclosure to the wrong recipient (email, API response, printout).
- Loss of availability (deletion, ransomware, unrecoverable failure).
- Leaked authentication material giving access to personal data.
- Publication of personal data in a public source-code repository or open log.

At suspicion, the team's task is to:

1. **Stop the breach** (withdraw access, rotate the secret, remove data where possible).
2. **Preserve evidence** (logs, commit history, affected records) before cleanup.
3. **Notify internally immediately:** security champion, PVO, and line manager.
4. **Document** what, when, scope, mitigation, and notified parties in Nav's
   designated template or system.

The controller, supported by PVO, assesses and sends the formal Datatilsynet
report within 72 hours.

### When to notify — DPIA prior consultation

If a DPIA shows the processing would retain high risk after mitigation, conduct
**prior consultation** with Datatilsynet before processing starts (GDPR Article
36). This is separate from incident notification and is initiated through PVO.

### What to notify

An incident report to Datatilsynet typically contains:

- Nature of the breach (access, disclosure, or loss).
- Categories and approximate number of affected data subjects.
- Categories and approximate number of affected records.
- Likely consequences for data subjects.
- Measures taken or proposed to handle the breach and reduce harm.
- Contact point (PVO) for follow-up.

When the breach is likely to create **high risk** to data-subject rights, notify
the affected data subject as well (GDPR Article 34). That is a separate process
owned by the controller.

### Document every case

Document every breach internally, including those not reported to Datatilsynet.
The record supports supervision, learning, and later assessment.

## Further reading

- `../SKILL.md` — `accessPolicy` assessment, escalation to the security champion,
  and delivery flow.
- `data-and-logging.md` — PII classification, standard logs, and CEF audit logging.
- `gdpr-privacy.md` — Nav-specific PII classification, retention, and minimisation.
- `api-security.md` — Nav-Call-Id, Nav-Consumer-Id, and `accessPolicy` as primary mechanism.
- `/auth-overview` — JWT validation, TokenX/Azure AD, `pid`/NAVident/`azp` claim, Texas sidecar.
- `/kotlin-ktor` — StatusPages/ApiError error contract and CallId/MDC.
- `/flyway-migration` — constraints and PII columns in migrations.
- [sikkerhet.nav.no](https://sikkerhet.nav.no) — Nav's security Golden Path and authoritative guidance.
