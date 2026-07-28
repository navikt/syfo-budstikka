---
name: grill-with-docs
description: "Stress-test a design while recording agreed durable terms and decisions."
disable-model-invocation: true
---

# Grill with docs

Run this manual workflow only inside an active Grillmester session. If another
outer role is active, stop and ask the user to switch with
`copilot --agent grillmester --model claude-opus-5 --context default`; do not
perform the workflow in that role.

Run `/grilling` with `/domain-modeling` loaded. Follow the repository's
configured domain-documentation and language guidance.

- Add or sharpen a canonical term only after the user agrees on its meaning.
- Record a hard-to-reverse, surprising trade-off through the repository's ADR
  process only after the choice is explicit.
- Update the context index only when its current model or navigation changes.
- Keep active implementation detail in the task-scoped brief.

Finish by summarizing the decisions, documentation changed, and remaining
uncertainty. Documentation and tracker writes always require explicit user
confirmation.
