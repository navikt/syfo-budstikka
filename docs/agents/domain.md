# Domain documentation

This is a **single-context** Kotlin/Ktor repository. Read narrowly and in this
order:

1. `docs/context.md` for the current mental model and pointers.
2. `docs/glossary.md` for canonical domain terms.
3. Relevant files in `docs/adr/` for binding, hard-to-reverse decisions.
4. The named entry in `docs/decisions.md` when code or documentation refers to
   a concrete `Bnn` decision. Do not load the complete register for ordinary
   orientation.
5. Topic documents such as `docs/kontrakt.md`, `docs/flyt.md`,
   `docs/datamodell.md`, `docs/ferdigstill.md`, `docs/migrering.md`,
   `docs/teknologi.md`, and `docs/teststrategi.md` when needed.

`docs/decisions.md` is the canonical definition source for active `Bnn`
references, not a work log or general onboarding document. Supersession must be
recorded explicitly on the affected entry or in the replacing ADR. Put
difficult durable decisions in an ADR and sharpen canonical domain terminology
in the glossary; never use a global work file as the source of truth.
