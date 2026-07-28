---
description: "Applies when a change contains application, build, or deployment code; preserves competence by explaining decisions and identifying red-zone work."
applyTo: "**/*.kt, **/*.kts, **/*.sql, **/*.yaml, **/*.yml, **/Dockerfile*, **/*.dockerfile"
---

# Deliberate AI use

Humans own durable domain, architecture, and contract decisions; an agent may
implement them. Explain **why** a choice was made, not only what changed.

Identify the red zone explicitly: authentication and security, business rules,
data models, and state machines. Their design and relevant ADRs must be
resolved before the work is considered complete. In a Grillmester/Kokk route,
apply the brief's Inspector risk contract. Direct Barista work stops and routes
to Grillmester when a red-zone or other R3/R4 characteristic appears; one
optional Inspector review may be offered only for material upper-R2 work
without red flags.

Clarify the desired engagement level early when relevant:

- **Full delegation:** give a short rationale and a clear result.
- **Guided:** explain options, risk, and edge cases. Especially in the red
  zone, ask the participant to explain the decision back.

Never encourage blind copy-paste, omit error handling, or present an
unjustified security choice as complete.
