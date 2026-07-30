# Domain documentation

This is a single-context Kotlin/Ktor service. The following is a load order,
not a general authority ranking. Load domain material narrowly:

1. Read `docs/context.md` for orientation and the current decision record.
2. Read `docs/glossary.md` for canonical domain terms.
3. Read only the relevant ADRs in `docs/adr/` for binding,
   hard-to-reverse decisions.
4. When a concrete `Bnn` decision is named, locate that entry directly in
   `docs/context.md` instead of loading the whole file.
5. Load the relevant topic document only when needed:
   `docs/kontrakt.md`, `docs/flyt.md`, `docs/datamodell.md`,
   `docs/ferdigstill.md`, `docs/migrering.md`, `docs/teknologi.md`,
   `docs/teststrategi.md`, or `docs/helsesjekk.md`.

Each source owns a different question:

- Executable code, tests, and published contracts establish current behavior.
- Relevant ADRs define binding, hard-to-reverse architectural intent.
- `docs/glossary.md` defines canonical prose vocabulary.
- `docs/context.md` records current direction, status, and named `Bnn`
  decisions.

Do not use `docs/context.md` as source text for runtime logs, API errors, or
ordinary code comments. Reference an ADR in code only when it explains a
non-trivial trade-off that the code cannot make clear on its own.

Treat discrepancies as findings to resolve, not as permission to choose a
convenient source silently.
