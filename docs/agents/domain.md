# Documentation ownership

This is the repository's only local policy for maintained documentation. It
maps portable domain-modeling skills to this service. Skills may point here,
but must not define competing local paths, ADR formats, or linking rules.

## Put each answer in one place

- Code and tests own current behaviour and executable scenarios.
- Topic documentation owns maintained contracts, subsystem explanations, and
  operator guidance.
- `docs/glossary.md` owns canonical domain vocabulary only. It is not a spec,
  implementation catalogue, or decision log.
- ADRs explain qualifying durable choices and their non-obvious rationale.
  They are shared context for people and agents working in the affected area.
- Issues, plans, and decision maps own task scope, unresolved choices, and
  temporary working state.
- KDoc explains a public contract or non-obvious invariant. An ordinary code
  comment explains a local why that the code cannot express. Neither contains
  decision history, ADR links, or `Bnn` references.
- Git history owns removed history. Do not replace deleted prose with an
  archive, register, or index.

When two sources disagree, resolve the conflict in the source that owns the
answer. Do not copy the explanation into more places.

## Local paths

- The portable `CONTEXT.md` glossary maps to `docs/glossary.md`. Do not create
  a root `CONTEXT.md`.
- Keep the glossary's Norwegian headings and `_Unngå_` marker. `_Avoid_` is
  only the portable fallback when a repository has no local format.
- This is a single-context service. A context map requires a separate
  architectural decision.
- `docs/decisions.md` is a frozen lookup for existing `B1`–`B63` references.
  Do not add entries or new references. Report incorrect entries as cleanup
  findings until the register is retired.

## Before writing domain documentation

Update the glossary or create an ADR only when the user asks for it, invokes
`/domain-modeling` or `/grill-with-docs`, or accepts a recommendation to use
that workflow. Finding a candidate does not by itself permit a repository
change.

Until then, keep candidates in the conversation, active issue, plan, or an
explicitly selected task-local scratch file. Routine contract or operator
documentation that is part of an approved implementation remains normal
implementation work.

## Read only what the task needs

1. Read code and tests for current behaviour.
2. Read `docs/glossary.md` when canonical domain language matters.
3. Read the relevant ADR before work that needs or could contradict its
   durable rationale.
4. Read only the relevant maintained topic document:
   `docs/sende-varsler.md`, `docs/flyt.md`, `docs/datamodell.md`,
   `docs/ferdigstill.md`, `docs/migrering.md`, `docs/teknologi.md`,
   `docs/teststrategi.md`, or `docs/helsesjekk.md`.
5. Look up a named legacy `Bnn` entry directly. Never load the whole register
   as background context.
6. Read `docs/context.md` only for repository orientation or overall status.

Code and tests still establish what the system does. A `besluttet` ADR records
accepted intent. The legacy `Akseptert` status in ADR 0001 means the same until
that file is audited. A `foreslått` ADR is planning input and binds work only
when the active task explicitly adopts it. When a new ADR omits status, treat
it as accepted.

## ADR gate and local form

`/domain-modeling` owns the eligibility check and writing workflow. Create an
ADR only when the choice is all three:

1. hard to reverse at meaningful cost;
2. surprising without its context; and
3. the result of a real trade-off between credible alternatives.

If one condition is missing, keep the information in its normal owner. New
ADRs live in `docs/adr/`; `docs/agents/language-policy.md` owns their language.
Increment the highest existing four-digit number and preserve intentional
gaps. Use a descriptive filename and a title that state both the scoped subject
and the decision, so the directory listing and heading reveal when the ADR is
relevant. Avoid vague titles that name only a topic. The current local form
defaults to short prose:

```md
# ADR NNNN — Kort tittel

{1–3 setninger om konteksten, valget og hvorfor det ble tatt.}
```

Keep one decision per ADR. Add status, date, related links, alternatives,
consequences, or sections only when they improve understanding or lifecycle
handling. A persisted proposal, rejection, or superseded decision must state
its status.

ADRs are meant to guide later work, but source code must remain understandable
without following a decision link. A maintained topic document may link to a
relevant ADR when its rationale is needed there. Do not add ADR links to KDoc
or ordinary comments, reciprocal links, or decision catalogues.
