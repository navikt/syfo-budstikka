---
name: handoff
description: "Compact unfinished work into a short, task-scoped handoff for a new thread or person."
disable-model-invocation: true
---

# Handoff

Compress only what the next work needs. A handoff is a task-scoped message, not
a global file whose name a later session must guess.

1. Use and state the existing issue/PR reference or stable slug.
2. Reference docs and diff rather than copying them.
3. Update the same brief, an issue comment, or the conversation. If a local
   file is needed, create an owner-only task directory outside the repository
   (for example with `mktemp -d`) and state the canonical absolute artifact
   path inside it. Never place the handoff directly in a shared temporary
   directory.
4. Do not write FNR, names, tokens, passwords, or other sensitive data.

```markdown
## Handoff: <task>
Current state: <branch, issue/PR, completed part>
Decisions: <ADRs and important clarifications>
Remaining: <short, concrete points>
Next step: <first command or action>
Evidence: <command + result + exit code, or what is missing>
Sources: <brief, docs, issue/PR, relevant files>
```

Finish with the recommended next skill. Durable choices belong in ADRs and the
glossary, not the handoff.
