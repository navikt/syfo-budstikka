# Data and logging — Nav context

Read this when review touches personal data, standard logs, or audit logs. The
main skill owns `accessPolicy`, escalation, and the delivery flow.

## PII classification in Nav

Nav handles personal data at four protection levels. Incorrect classification is
the most common root cause of serious incidents.

| Level | Typical data | Handling |
|------|--------------|------------|
| **Strictly confidential** | Health information, diagnoses, sick-leave certificates, people exposed to violence/code 6, child-welfare data | Encryption at rest and in transit, strict access control, CEF audit on display (WARN), dedicated DPIA |
| **Confidential** | National identity number (fnr), D-number, code 7, sensitive benefit data | Never in standard logs, CEF audit on display, access control per case/user |
| **Internal** | Name, address, phone, email, non-sensitive benefit status | Data minimisation, need-to-know access, documented retention |
| **Open** | Public statistics, anonymised aggregates | Normal access; verify anonymisation resists linkage attacks |

`syfo` handles absence and sick-leave certificates: the fact that “a user is on
sick leave” is **strictly confidential** implicit health information. Never use
real fnr; use `00000000000` or an explicitly synthetic Skatteetaten test series
in code, examples, and tests. Read `nav-threat-model.md` for DPIA and audit requirements.

## PII in logs

The repository logs through Logback (`src/main/resources/logback.xml`). Use
structured fields, never PII in message text:

```kotlin
// OK — correlation ID and subject, no PII
log.info("Behandler sak", kv("callId", MDC.get("callId")), kv("tema", sak.tema))

// Never — fnr, name, diagnosis, or benefit data in the standard log
log.info("Behandler sak for ${bruker.fnr}")
```

Set `Nav-Call-Id` at ingress through Ktor `CallId` and put it in MDC for
cross-service correlation (see `/kotlin-ktor`). Log display of personal data to
Nav employees in **CEF format** through a dedicated `auditLogger`, never the
standard log. Read `nav-threat-model.md` for format and fields.
