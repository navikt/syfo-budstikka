# Domain documentation

This is a single-context Kotlin/Ktor service. This file is the repository
adapter for portable domain-modeling skills as well as a load order. It
overrides their default artifact paths and formats, but not their decision
criteria.

## Portable-skill mapping

- Treat a portable skill's `CONTEXT.md` glossary as `docs/glossary.md` here. Do
  not create a root `CONTEXT.md`.
- Preserve the glossary's established Norwegian headings and `_Unngå_` marker.
  The portable `_Avoid_` marker is only a fallback for repositories without a
  local format.
- Keep `docs/glossary.md` to domain vocabulary only. Put maintained domain
  detail in the relevant topic document, task-scoped choices in the issue or
  plan, and only qualifying durable decisions in an ADR.
- Do not create a context map for this single-context repository. Establishing
  multiple bounded contexts and their documentation ownership is a separate
  architectural change.
- `docs/decisions.md` is a compatibility register for existing `Bnn`
  identifiers. Locate a named entry directly and do not mint a new `Bnn`
  identifier by default. New durable decisions use ADRs when they pass the
  three-part ADR gate.

## Load order

The following is a load order, not a general authority ranking. Load domain
material narrowly:

1. Go straight to the narrowest source that answers the question.
2. Read `docs/glossary.md` for canonical domain terms.
3. Read only the relevant ADRs in `docs/adr/` for binding,
   hard-to-reverse decisions.
4. When a concrete `Bnn` decision is named, locate that entry directly in
   `docs/decisions.md` instead of loading the whole file.
5. Load the relevant topic document only when needed:
   `docs/kontrakt.md`, `docs/flyt.md`, `docs/datamodell.md`,
   `docs/ferdigstill.md`, `docs/migrering.md`, `docs/teknologi.md`,
   `docs/teststrategi.md`, or `docs/helsesjekk.md`.
6. Read `docs/context.md` in full only when you actually need orientation or
   overall status. Do not load it to answer a question a narrower source
   already answers.

Each source owns a different question:

- Executable code, tests, and published contracts establish current behavior.
- Relevant ADRs define binding, hard-to-reverse architectural intent.
- `docs/glossary.md` defines canonical prose vocabulary.
- `docs/decisions.md` is the canonical compatibility register for the existing
  `Bnn` decisions.
- `docs/context.md` records current direction, status, and orientation.

Do not use `docs/context.md` as source text for runtime logs, API errors, or
ordinary code comments. Reference an ADR in code only when it explains a
non-trivial trade-off that the code cannot make clear on its own.

Treat discrepancies as findings to resolve, not as permission to choose a
convenient source silently.

## ADR house format

`/domain-modeling` is the only ADR-producing skill. New ADRs live in
`docs/adr/`, increment the highest existing number, and preserve intentional
numbering gaps. Use the established repository form:

```md
# ADR NNNN — Kort tittel

- Status: foreslått | besluttet | forkastet | erstattet av ADR NNNN
- Dato: YYYY-MM-DD
- Relatert: ADR-er, Bnn-oppføringer, issue eller topic-dokument; ellers ingen

## Kontekst

## Beslutning

## Konsekvenser
```

The numbered title, `Status`, `Dato`, `Relatert`, `Kontekst`, `Beslutning`, and
`Konsekvenser` are required for new ADRs. Add `Alternativer vurdert` or another
specialized section only when it adds value. Keep one focused decision per ADR.
