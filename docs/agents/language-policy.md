# Language policy

Use language deliberately by artifact type.

## Target state

- `README.md` and user-facing product copy are Norwegian Bokmål.
- Established Norwegian domain code identifiers remain Norwegian, for example
  `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, and
  `Brev`. A glossary `_Unngå_` note governs prose vocabulary; it does not
  silently rename an established type or published contract.
- Technical and mechanical code identifiers are English.
- Agent-facing material, technical documentation, ADR prose, issues, pull
  requests, commit messages, scripts, and technical operator interfaces are
  English.
- Preserve quoted Norwegian product copy and canonical Norwegian domain terms
  when they are the subject.

## Incremental migration

Existing Norwegian technical material migrates in small thematic changes.
New or substantively rewritten agent-facing and technical material is English;
do not expand an unrelated change merely to translate neighbouring files.
Preserve historical decision wording until it is deliberately migrated or
superseded.

Agents answer users in the user's language unless they are creating or editing
an artifact governed by this policy. Do not rename a published contract,
stable path, schema identifier, or historical identifier solely to translate
it. Never edit an applied Flyway migration for language or style.
