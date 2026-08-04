# Documentation ownership

This file is the sole repository-local policy for maintained documentation. It
adapts portable domain-modeling skills to this single-context service. Skills
and instructions may point here, but must not redefine local artifact
ownership, ADR shape, or cross-linking rules.

## One owner per question

- Code and tests own current behaviour and executable scenarios.
- Topic documentation owns maintained contracts, subsystem explanations, and
  operator guidance.
- `docs/glossary.md` owns canonical domain vocabulary only. It is not a
  context map, implementation catalogue, or decision log.
- ADRs own only qualifying durable choices and their non-obvious rationale.
- Issues, plans, and decision maps own task scope, unresolved choices, and
  temporary working state.
- KDoc explains a public contract or a non-obvious invariant. An ordinary code
  comment explains a local why that the code cannot express. Neither carries
  decision history, ADR links, or `Bnn` references.
- Git history owns removed history. Do not create an archive, replacement
  register, or navigation shell to preserve deleted prose.

Treat discrepancies as findings to resolve in the owning source, not as
permission to duplicate an explanation elsewhere.

## Portable-skill mapping

- Treat a portable skill's `CONTEXT.md` glossary as `docs/glossary.md` here.
  Do not create a root `CONTEXT.md`.
- Preserve the glossary's established Norwegian headings and `_Unngå_` marker.
  The portable `_Avoid_` marker is only a fallback for repositories without a
  local format.
- Do not create a context map for this single-context repository. Establishing
  multiple bounded contexts is a separate architectural change.
- `docs/decisions.md` is a temporary, frozen lookup for existing `B1`–`B63`
  references. Never create a `Bnn` identifier, extend an entry, or add a new
  `Bnn` cross-reference. Report drift as a cleanup finding instead.

## Durable write boundary

Write a glossary term or ADR only when the user directly asks to update domain
documentation, explicitly invokes `/domain-modeling` or
`/grill-with-docs`, or accepts a recommendation to enter that documented
workflow. Autonomous skill selection, an ADR candidate from another skill, or
ordinary design discussion does not authorise a durable write.

Before authorisation, keep candidates in the conversation, active issue, plan,
or explicitly selected task-local scratch space. Explain why documentation
would help and wait for the user's choice.

Routine contract or operator documentation required by an already approved
implementation remains normal implementation scope. New terms and ADR
candidates found during that work still follow this boundary.

## Load narrowly

Go directly to the narrowest source that answers the question:

1. Read code, tests, and published contracts for current behaviour.
2. Read `docs/glossary.md` when canonical domain language matters.
3. Read only the relevant ADR when durable rationale matters.
4. Read the relevant topic document for maintained detail:
   `docs/kontrakt.md`, `docs/flyt.md`, `docs/datamodell.md`,
   `docs/ferdigstill.md`, `docs/migrering.md`, `docs/teknologi.md`,
   `docs/teststrategi.md`, or `docs/helsesjekk.md`.
5. Look up a named legacy `Bnn` entry directly; never load the register as
   ambient context.
6. Read `docs/context.md` only for repository orientation or overall status.

An ADR records architectural intent, not implementation evidence. Existing
`besluttet` ADRs express accepted intent; `foreslått` ADRs are planning
inputs. The legacy `Akseptert` status in ADR 0001 means `besluttet` until
that file is deliberately audited. A statusless ADR records an accepted
decision. Code and tests still establish what the system currently does.

## ADR gate and local form

`/domain-modeling` owns eligibility and the durable-write workflow. This
repository applies its three-part gate unchanged. Create an ADR only when the
choice is all three:

1. hard to reverse at meaningful cost;
2. surprising without its context; and
3. the result of a real trade-off between credible alternatives.

If any condition is missing, keep the information in its normal owner. Short
prose is the default:

```md
# Short title

{In 1–3 sentences: the context, the decision, and why it was chosen.}
```

New ADRs live in `docs/adr/`, increment the highest existing four-digit number,
and preserve intentional gaps. Keep one focused decision per ADR. Status, date,
related links, considered alternatives, consequences, and fixed section
scaffolding are optional; include them only when they materially improve
understanding or lifecycle handling.

Unresolved choices normally remain in the issue, plan, or decision map. A
deliberately persisted proposal, rejection, or superseded decision must carry a
status. Keep any status aligned with reality.

Do not propagate decision links by default. Outside KDoc and ordinary code
comments, add a targeted ADR link only when a reader needs its non-obvious
rationale at the owning seam, and keep the referring artifact understandable
without following the link. Do not add reciprocal links or catalogues merely
for completeness.
