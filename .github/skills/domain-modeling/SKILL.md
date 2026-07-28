---
name: domain-modeling
description: "Build and sharpen the domain model, canonical terminology, glossary, and durable decisions."
disable-model-invocation: true
---

# Domain modeling

Run this manual workflow only inside an active Grillmester session. If another
outer role is active, stop and ask the user to switch with
`copilot --agent grillmester --model claude-opus-5 --context default`; do not
perform the workflow in that role.

Actively build and sharpen the domain model while designing: challenge terms,
invent edge scenarios, and record glossary entries and decisions when they
crystallize. Merely reading `docs/glossary.md` is a normal habit; use this skill
when changing the model, normally inside `/grill-with-docs`.

## Durable locations

Domain documentation lives in committed `docs/`; task-scoped briefs are working
memory:

```text
docs/
├── glossary.md           ← one term per line: term → precise definition
├── context.md            ← short current mental model and index
└── adr/
```

Create files lazily. In a large repository with bounded contexts, place a term
with its owning context and record relationships at the start of `glossary.md`.
Ask when ownership is unclear.

## During the session

- Flag collisions with existing glossary language immediately.
- Replace vague/overloaded wording with one canonical term and put alternatives
  under `_Avoid_`. `ident`, `fnr`, and `aktørId` are not Nav synonyms.
- Stress-test relations with concrete edge scenarios, then cross-check claims
  against actual `no.nav.budstikka` code using `rg` and reading.
- After the user has selected Grill with docs and separately confirmed the
  documentation write, record an agreed term in `glossary.md` using
  [references/glossary-format.md](references/glossary-format.md). Keep it free
  of implementation details; technical choices belong in ADRs and active status
  in the task brief. Update `context.md` only when its model/index changes.

Offer an ADR only when all three are true: the choice is hard to reverse, it
would surprise a future reader without context, and it resulted from a real
trade-off. Otherwise do not create one. Use
[references/adr-format.md](references/adr-format.md).

The resulting glossary/ADRs inform design, briefs, and verification. Never
write durable documentation without the active Grill with docs workflow and
its explicit write confirmation. For a clarification that changes architecture
or access across teams, recommend the manual Nav architecture review workflow
and wait for the user to select it.
