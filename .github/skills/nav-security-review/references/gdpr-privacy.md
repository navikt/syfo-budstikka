# GDPR and privacy — Nav signals

Generic GDPR theory (legal basis, right to be forgotten, anonymisation versus
pseudonymisation, retention-job patterns, and consent history) is out of scope.
This reference covers Nav-specific classification and links to authoritative sources.

## Nav-specific personal-data classification

Nav uses the four PII levels defined in
[data-and-logging.md](data-and-logging.md): **strictly confidential**,
**confidential**, **internal**, and **open**. Important Nav-specific details:

- **Sick leave is strictly confidential.** In the `syfo` domain, sick-leave
  certificates, diagnoses, and the fact that a user is on sick leave are implicit
  health information → strictly confidential.
- **Benefit data can be implicit classification.** “A user receives AAP” or
  “disability benefit” is implicit health information → strictly confidential.
  Clarify classification for each benefit.
- **Code 6/7** (address protection/confidential address) must always be treated
  as strictly confidential, whatever the field.
- **National identity and D-numbers** are confidential. Never use real fnr in
  code, examples, or tests. Placeholder: `00000000000`. A synthetic Skatteetaten
  test series is allowed but must be marked explicitly.

## Links to authoritative sources

- **DPIA process:** Read `references/nav-threat-model.md`. A DPIA is required for
  new personal-data processing or material change.
- **CEF/ArcSight audit-log format:** Read `references/nav-threat-model.md` (the
  authoritative source in this skill).
- **Retention policy:** Document per processing activity with legal basis.
  Coordinate with the security champion and verify coverage of test data, export
  files, backups, Kafka topics, and analytics extracts.
- **Datatilsynet and supervisory inquiries:** Escalate to the security champion
  immediately. Do not answer directly.

## Data minimisation in practice

During review, ask whether every PII field in a data model is necessary for the
purpose. New fields require an updated processing basis, not only a Flyway
migration; use `/flyway-migration` when a migration adds or changes PII columns.
