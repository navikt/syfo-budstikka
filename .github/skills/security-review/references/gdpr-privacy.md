# GDPR and privacy — NAV signal

Generic GDPR theory (legal basis for processing, right to be forgotten, anonymization vs. pseudonymization, retention job patterns, consent history) is out of scope. This reference covers NAV-specific categorization and pointers to authoritative sources.

## NAV-specific categorization of personal data

NAV works with four PII levels defined in the SKILL.md table: **strengt fortrolig**, **fortrolig**, **intern**, **åpen**. Important NAV-specific clarifications:

- **Sykefravær is strengt fortrolig.** In the `syfo` domain, sykmeldinger, diagnoses and the fact that a user is sykmeldt are implicit health information → strengt fortrolig.
- **Benefits data can be classifiable implicitly.** "The user receives AAP" or "uføretrygd" is implicit health information → strengt fortrolig. Always clarify per benefit.
- **Kode 6/7** (address protection/confidential address) must be handled as strengt fortrolig regardless of field.
- **National identity numbers and D-numbers** are fortrolig. Never use a real fnr
  in code, examples or tests. Use `<SYNTHETIC_FNR>` in templates and
  documentation. When executable code requires a valid format, use Skatteetaten's
  synthetic test series and mark the test data explicitly.

## Pointers to authoritative sources

- **DPIA process**: See `references/nav-threat-model.md`. A DPIA is required for new processing of personal data or a substantial change.
- **CEF/ArcSight audit log format**: See `references/nav-threat-model.md` (the authoritative source in this skill).
- **Retention policy**: Documented per processing activity with its legal authority. Coordinate with the security champion and verify that test data, export files, backups, Kafka topics and analytics extracts are also covered by the policy.
- **Datatilsynet / supervisory enquiries**: Escalate to the security champion immediately. Do not answer directly.

## Data minimization in practice

During review: ask whether every PII field in a data model is necessary for the purpose. New fields require an updated legal basis for processing, not just a Flyway migration.
