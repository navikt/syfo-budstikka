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

## Durable write boundary

During grilling or design, a direct request to update domain documentation,
an explicit `/domain-modeling` or `/grill-with-docs` invocation, or the user's
acceptance of a documented-workflow recommendation authorises durable glossary
and ADR writes. Autonomous model selection or a candidate reported by another
skill does not. Before authorisation, keep candidates in the conversation or
`.grill/`, explain why documenting them would help, and wait for the user's
choice.

Routine contract or operator documentation required by an already approved
implementation remains normal implementation scope; it does not require a
second workflow choice. New domain terms and ADR candidates discovered during
that work still follow the boundary above.

## Load order

The following is a load order, not a general authority ranking. Load domain
material narrowly:

1. Go straight to the narrowest source that answers the question.
2. Read `docs/glossary.md` for canonical domain terms.
3. Read only the relevant ADRs in `docs/adr/`, and check their status before
   using them. Only `besluttet` ADRs are binding architectural intent;
   `foreslått` ADRs describe planned choices, not current implementation.
   Treat the legacy `Akseptert` status in ADR 0001 as `besluttet` until that
   historical file is deliberately normalised.
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
- `besluttet` ADRs define binding, hard-to-reverse architectural intent. The
  legacy `Akseptert` status in ADR 0001 has the same meaning.
  `foreslått` ADRs are planning inputs. Neither overrides executable evidence
  about current behavior.
- `docs/glossary.md` defines canonical prose vocabulary.
- `docs/decisions.md` is the canonical compatibility register for the existing
  `Bnn` decisions.
- `docs/context.md` records current direction, status, and orientation.

Do not use `docs/context.md` as source text for runtime logs, API errors, or
ordinary code comments. Reference an ADR in code only when it explains a
non-trivial trade-off that the code cannot make clear on its own.

Treat discrepancies as findings to resolve, not as permission to choose a
convenient source silently.

## ADR lifecycle

An ADR written while grilling, designing, or planning starts as `foreslått`.
Do not present it as current architecture or let downstream work assume the
corresponding change already exists.

Promote it to `besluttet` only when the owning team has accepted the decision
and implementation or delivery evidence shows that it is in force. If the work
is abandoned, mark it `forkastet`. If another decision replaces it, mark it
`erstattet av ADR NNNN` and link both directions. Delivery must reconcile every
related `foreslått` ADR instead of leaving a planned decision looking current.

Code, tests, and published contracts remain the source for what the system
actually does. ADR status records architectural intent and lifecycle; it is not
implementation evidence.

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
