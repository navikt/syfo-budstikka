# Skill invocation

Repository skills live under `.github/skills/`. For every new or materially
updated skill, express invocation through supported GitHub Copilot CLI
frontmatter:

- Manual-only: set `disable-model-invocation: true`; `user-invocable` defaults
  to `true`.
- Model-only: set `user-invocable: false` and allow model invocation.
- Both: omit both fields, or leave `user-invocable: true` without disabling
  model invocation.

The skill `description` supplies discovery and automatic-selection signals;
frontmatter constrains which invocation routes are available. Do not emulate
invocation policy with comments in the skill body or invent runtime
parameters.

The complete field list and defaults are documented in the
[GitHub Copilot CLI skills reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference#skills-reference).
Existing skills are reclassified only in a dedicated, reviewable skill change.

## Precedence over existing authoring guidance

`/writing-great-skills` currently states that skill frontmatter must contain
only `name` and `description`. That predates this document and would reject the
supported fields above. This document takes precedence on frontmatter. The
authoring skill is reconciled when the skill core is imported, not here.
