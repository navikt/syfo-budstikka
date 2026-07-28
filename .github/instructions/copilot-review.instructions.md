---
description: "Always-on review guidance: report only concrete, consequential findings in the current diff."
applyTo: "**"
---

# Copilot code review

Give a small number of concrete comments with observable risk and a suggested
change. Prioritize correctness, security or privacy, compatibility, operations,
and scope. Anchor each finding to the changed code and explain impact.

Use the task-scoped brief, relevant ADRs, and existing repository patterns as
the specification. Do not invent requirements or report duplicates, nits, or
style preferences without consequence. With no findings, say so briefly.
