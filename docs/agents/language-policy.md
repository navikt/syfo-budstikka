# Language policy

Use language deliberately by artifact type.

## Target state

- `README.md` and user-facing product copy are Norwegian Bokmål.
- Established Norwegian domain code identifiers remain Norwegian, for example
  `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, and
  `Brev`. A glossary `_Unngå_` note governs prose vocabulary; it does not
  silently rename an established type or published contract.
- Technical and mechanical code identifiers are English.
- Agent-facing material, scripts, and technical operator interfaces are
  English.
- Preserve quoted Norwegian product copy and canonical Norwegian domain terms
  when they are the subject.

Issue, pull-request, commit-message, and ADR prose language is deliberately
**not** settled by this document. `docs/agents/domain.md` owns repository
documentation placement and ADR form; `/domain-modeling` applies the portable
ADR gate and creates durable domain documentation after authorisation;
`/klarsprak` may polish prose without changing that contract. Other artifact
language follows the skill that produces it, including `/conventional-commit`,
until a later change migrates it on purpose. Do not treat this file as
authority to switch them.

## Incremental migration

New agent-facing and technical artifacts are English. Existing Norwegian
artifacts migrate in small thematic changes, but each changed artifact stays
internally coherent: retain its dominant language until a dedicated change can
migrate the whole file or a cohesive artifact group. Do not insert isolated
English paragraphs into an otherwise Norwegian contract merely because those
paragraphs are new, and do not expand an unrelated change to translate
neighbouring files. Preserve historical decision wording until it is
deliberately migrated or superseded.

Agents answer users in the user's language unless they are creating or editing
an artifact governed by this policy. Do not rename a published contract,
stable path, schema identifier, or historical identifier solely to translate
it. Never edit an applied Flyway migration for language or style.
