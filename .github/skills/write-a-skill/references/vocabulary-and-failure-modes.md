# Vocabulary and failure modes

Full meanings of terms in `SKILL.md`, plus a diagnostic catalog for skills that
do not behave as intended. Load on demand through the context pointers in the
main skill.

## Vocabulary

- **Predictability** — Copilot follows the same *process* each run, not produces
  identical text. The root quality every other technique serves.
- **Trigger signal** — a situation signal that should make Copilot load the
  skill: task type, user phrase, touched file/area, or `/name` call.
  `description` collects these; it is not a summary.
- **Branch** — a distinct situation or path through a skill. Give each branch
  one trigger; phrases describing the same branch are duplication.
- **Leading word** — a compact pretrained term anchoring a complete behaviour in
  few tokens (*tracer bullet*, *idempotent*, *vertical slice*, *grilling*,
  *tight*, *red/green*). It anchors execution in the body and invocation in the description.
- **Information hierarchy** — material ranked by how immediately Copilot needs
  it: steps in SKILL.md → reference in SKILL.md → external file in `references/`.
- **Step** — an ordered SKILL.md action, the primary tier. It ends in a
  completion criterion.
- **Reference** — a definition, rule, or fact to look up as needed, not execute
  in order.
- **Completion criterion** — the condition proving a step is finished. It must
  be *checkable* (finished or not), and where it matters *exhaustive*. A vague
  criterion invites premature completion.
- **Progressive disclosure** — moving heavy material down the hierarchy, out of
  SKILL.md and into `references/`, so the top remains readable.
- **Context pointer** — the SKILL.md sentence directing Copilot to an external
  reference. Wording, not destination, determines how reliably material is
  reached. Name what the file contains.
- **Contract** — a positive, checkable specification of what must hold (the
  repository's pattern; compare `grill-with-docs` and `tdd`), preferred over a
  prohibition list.
- **Granularity** — how finely skills are divided. Each new skill costs
  always-loaded description context; split only when the cut earns it.
- **Legwork** — repository investigation inside the task (reading code or an
  ADR) driven by a demanding completion criterion.

## Failure modes

- **Premature completion** — ending a step before it is finished because focus
  drifts to *being done*. Defence order: tighten the completion criterion first
  (cheap and local); only when it is unavoidably vague **and** rushing is
  observed, hide following steps by splitting the sequence.
- **Duplication** — the same meaning in multiple places. It costs maintenance
  and tokens, and inflates the meaning's apparent rank in the hierarchy. Collapse
  it to one source of truth.
- **Sediment** — old layers kept because adding feels safe and removing feels
  risky. It is the default fate of any skill without pruning discipline.
- **Sprawl** — a skill is simply too long even when every line is live and unique.
  Use the hierarchy: disclose references behind pointers, and split by branch or
  sequence so each path carries only what it needs.
- **No-op** — a line the model already follows by default, so context is spent
  saying nothing. Test whether it changes behaviour versus default. A weak
  leading word (*be thorough* when Copilot already is) is a no-op; replace it
  with a stronger one (*relentless*), not another technique.
