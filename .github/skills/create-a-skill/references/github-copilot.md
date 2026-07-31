# GitHub Copilot Skill Adapter

Use this reference only when the target runtime is GitHub Copilot.

## Project structure

GitHub Copilot currently searches these project locations in priority order:

1. `.github/skills/`
2. `.agents/skills/`
3. `.claude/skills/`

Prefer the target repository's existing supported tree instead of creating a
parallel convention. Whichever supported root the repository selects, use this
shape:

```text
<selected-skill-root>/<skill-name>/
├── SKILL.md
├── references/   # optional, progressively disclosed reference
├── scripts/      # optional, deterministic helpers
└── assets/       # optional, templates or output inputs
```

Keep references one level below `SKILL.md` so the skill can point to them
directly. Follow repository instructions when they define a different supported
layout.

## Supported invocation fields

Use only fields supported by the installed GitHub Copilot runtime:

- `name` — required; match the kebab-case directory and slash name.
- `description` — required; model-facing discovery signals for a
  model-reachable skill, or a concise human-facing summary for a manual skill.
- `disable-model-invocation: true` — manual-only; `user-invocable` defaults to
  true.
- `user-invocable: false` — model-only when model invocation remains enabled.
- Omit both invocation flags for a skill reachable by both human and model.
- `argument-hint` — optional picker guidance for genuine user input.

Repository invocation policy wins when it narrows these choices. Verify current
field support from the
[GitHub Copilot CLI skills reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference#skills-reference)
before adding other frontmatter keys.

## Description checks

For model-reachable skills:

- name the job and distinct triggering branches;
- use one trigger per branch rather than synonym lists;
- distinguish close neighboring skills;
- keep implementation steps out of the description.

For manual-only skills, describe what the human selects. Do not simulate model
discovery with trigger-heavy prose.

## Validation

From the target repository root:

```sh
copilot -C "$PWD" skill list --json
```

Confirm that the skill parses, is enabled, and is listed from the expected
project source. The JSON listing does not prove invocation reachability. Verify
the intended mode as follows:

- manual-only — frontmatter, picker presence, explicit slash invocation, and no
  autonomous selection;
- model-only — frontmatter, picker absence, and fresh-session positive and
  close-negative selection;
- both — frontmatter, picker presence, explicit slash invocation, and
  fresh-session positive and close-negative selection.

Treat parser errors from the target skill as blockers. Report unrelated,
pre-existing parser errors separately rather than claiming a clean global
result.

Also verify relative Markdown links, executable script permissions, direct
callers, and safe representative behavior for every distinct branch.
