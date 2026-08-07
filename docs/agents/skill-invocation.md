# Skill invocation

This repository targets GitHub Copilot CLI and keeps project skills under
`.github/skills/`. Do not introduce a parallel `.agents/skills/` or
`.claude/skills/` tree.

Use `/create-a-skill` for every new or materially updated skill. It is
available through both automatic selection and explicit slash invocation. Its
Copilot contract is the authoritative field, layout, and validation guidance;
this file records only repository policy.

Treat model reach as an intentional interface. Add it when Copilot must
discover the skill autonomously or another model-reachable skill must route to
it. Existing skills are reclassified only in a dedicated, reviewable skill
change.
