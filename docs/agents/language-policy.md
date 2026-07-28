# Language policy

Use language deliberately by artifact type:

- `README.md` and user-facing product copy are written in Norwegian Bokmål.
- Canonical domain terms remain Norwegian in code when the glossary defines
  them, for example `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`,
  `DittSykefravaer`, and `Brev`.
- All technical and mechanical code identifiers are English.
- Agent-facing material is English: `AGENTS.md`, Copilot instructions, agents,
  skills, agent contracts, validator output, and related scripts.
- Technical documentation, ADR prose, issue and pull-request text, and commit
  messages are English. Technical operator interfaces such as Grafana
  dashboards are also English. Preserve quoted Norwegian product copy and
  canonical domain terms when those are the subject.
- Existing `Bnn` entries in `docs/decisions.md` and preserved source material
  in `docs/legacy-context.md` retain their original Norwegian wording until
  deliberately migrated or superseded. Preserving decision intent and source
  fidelity takes precedence over a style-only translation. New or
  substantively updated entries are English.

Agents answer in the user's language unless they are creating or editing an
artifact governed by this policy. Do not rename a published contract, stable
path, or historical identifier solely to translate it; treat such a rename as
a compatibility decision. Never edit an applied Flyway migration for language
or style; append-only migration integrity takes precedence.
