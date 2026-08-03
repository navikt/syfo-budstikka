# AI collaboration depth

Use this guide when a user asks for a guided learning mode, is working in
unfamiliar technology, or wants to choose how much implementation-level
explanation they receive. It does not add a routine startup question or change
the repository's risk, review, or delivery policy.

## Why interaction style matters

NAV's 2026 developer survey found that 59 percent of respondents were concerned
that AI tools could weaken deep technical understanding. Anthropic's 2026
randomized study of developers learning an unfamiliar Python library found
lower average mastery with AI assistance than without it. Its small qualitative
interaction clusters ranged from weak results for blind delegation to strong
results when participants actively questioned generated code. The authors do
not claim that those interaction patterns are causal, and the study measured
short-term learning in one unfamiliar-library task.

These findings support deliberate comprehension, not mandatory manual coding or
explanations of every syntax choice.

## Collaboration modes

- **Delegated:** The agent owns implementation and verification. The human owns
  product and architecture choices and understands material red-zone changes.
  Use this for familiar, settled work unless the user asks for more guidance.
- **Guided:** The agent adds concise explanations and comprehension prompts,
  especially around unfamiliar technology or red-zone changes. Useful prompts
  include: why this approach, what can fail, and which edge cases matter.

Do not ask every user to choose a mode. Follow an explicit preference; otherwise
infer the lightest suitable mode from the task and explain material choices.
Prefer Guided when the user identifies as junior or explicitly wants to learn.
Never encourage blind or unreviewed copy-paste of generated code.

## Sources

- [NAV developer survey 2026](https://ki-utvikling.nav.no/nyheter/utviklerundersokelsen-2026)
- [Anthropic: How AI assistance impacts the formation of coding skills](https://www.anthropic.com/research/AI-assistance-coding-skills)
