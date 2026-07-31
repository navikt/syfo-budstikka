# Skill invocation

Repository skills live under `.github/skills/`. For every new or materially
updated skill, express invocation through supported GitHub Copilot CLI
frontmatter:

- Manual-only: set `disable-model-invocation: true`; `user-invocable` defaults
  to `true`.
- Model-only: set `user-invocable: false` and allow model invocation.
- Both: omit both fields, or leave `user-invocable: true` without disabling
  model invocation.

For a model-reachable skill, `description` supplies discovery and
automatic-selection signals. For a manual-only skill, it is a concise
human-facing summary. Frontmatter constrains which invocation routes are
available. Do not emulate invocation policy with comments in the skill body or
invent runtime parameters.

The complete field list and defaults are documented in the
[GitHub Copilot CLI skills reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference#skills-reference).
Existing skills are reclassified only in a dedicated, reviewable skill change.

## Authoring guidance

`/create-a-skill` is available through both automatic selection and explicit
slash invocation so the action-oriented rename preserves the previous
model-reachable authoring guidance. Other frontmatter fields are used only when
the GitHub Copilot CLI skills reference supports them and the skill's interface
needs them.
