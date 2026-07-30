---
name: grill-with-docs
description: A relentless interview to sharpen a plan or design, which also creates docs (ADR's and glossary) as we go.
disable-model-invocation: true
---

Run a `/grilling` session, using the `/domain-modeling` skill.

Apply the repository's progressive documentation seam in
`docs/agents/domain.md`: resolved vocabulary belongs in `docs/glossary.md`,
maintained detail in the relevant topic document, and a qualifying durable
trade-off in one focused ADR. Update `docs/context.md` only when repository
orientation or overall status changes. Keep task-scoped choices in the issue
or plan, and look up a named `Bnn` entry directly instead of preloading or
extending `docs/decisions.md`.

Load a NAV reference only after the active decision branch needs it:

- For a new service, a new application shape, or modernization, use
  [references/nav-arketyper.md](references/nav-arketyper.md).
- When data categories or privacy constraints are relevant or unclear, use
  [references/data-classification.md](references/data-classification.md).
- When a concrete NAV backend design needs a risk prompt, use
  [references/blind-spots.md](references/blind-spots.md).

Load only the matching reference, not the whole set.
