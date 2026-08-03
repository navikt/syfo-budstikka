---
description: "GitHub Copilot Code Review priorities for concrete, consequential findings in this repository."
applyTo: "**"
---

# GitHub Copilot code review

Only when acting as GitHub Copilot Code Review:

- Review only the current pull-request diff against its acceptance criteria,
  named decisions, and established repository patterns.
- Apply the six analysis axes in `.github/skills/review/SKILL.md` and relevant
  path-specific instructions.
- Prioritize correctness, security and privacy, compatibility and rollout,
  operations, then scope and maintainability.
- Report only concrete, consequential issues introduced by the diff. Anchor
  each finding to changed code, state the failure mode and impact, and suggest
  the smallest useful correction.
- Avoid invented requirements, speculative concerns, style-only nits, and
  duplicate findings. If there are no findings, keep the response brief.
- Use a short code example only when it makes the correction concrete.
- Apply the `security-review` skill when the diff crosses a security boundary.
