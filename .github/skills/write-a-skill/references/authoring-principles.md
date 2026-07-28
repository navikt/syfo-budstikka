# Principles for skill authors

This reference expands `/write-a-skill`. Read it when writing frontmatter,
needing trigger-text examples, choosing a disclosure level, or assessing language,
contracts, and splitting.

## Frontmatter and description

The standard case needs two fields:

- **`name`** — the kebab-case folder name, also the `/slash` name.
- **`description`** — tells Copilot what the skill does. For a model-invoked
  skill it also carries natural-language trigger signals; for a manually
  invoked skill it is a short human-facing picker summary.

Use optional fields only when behaviour needs them: `disable-model-invocation`
for manual invocation, `user-invocable` for internal skills, `allowed-tools` for
pre-approved tools, `argument-hint` for argument help, and `license` for a
separate distribution licence.

```text
# MODEL-INVOKED: Design production-safe Flyway migrations. Use when adding tables, columns, indexes, backfills, or rollback plans.
# USER-INVOKED: Compact unfinished work into a task-scoped handoff.
# WEAK: Used when /flyway-migration should be used.
```

Description rules:

- Choose invocation before writing discovery text.
- For model invocation, front-load the leading word or action and add
  `Use when ...` with one distinct trigger per branch. Synonyms for one branch
  are duplication.
- For manual invocation, set `disable-model-invocation: true`, keep a compact
  human-facing summary, and omit model trigger phrasing.
- Do not repeat the skill's slash name merely for discoverability; the catalog
  already carries `name`.
- Keep descriptions compact and follow the configured repository budget.
- Cut workflow identity already present in the body; keep only the action,
  trigger branches, and any reach clause another skill genuinely needs.

Copilot CLI lists manual descriptions too, but GitHub does not promise that
every catalog byte is injected into every model turn. Treat the full catalog as
a conservative upper-bound proxy and measure the model-invokable subset
separately.

## Progressive disclosure

Rank material as follows:

1. Steps in `SKILL.md`: ordered actions with a checkable, and where needed
   exhaustive, completion criterion.
2. Short reference in `SKILL.md`: definition, rule, or fact that must stay near.
3. External reference under `references/`: heavy material only some branches need.

Move material down the hierarchy when not every branch needs it. A context pointer
must state what the file contains and why it matters, for example “Read
`references/<name>.md` for the full A, B, and C implementation,” not a bare
link. Sibling skills keep error contracts, pagination, and mocking examples in
references; follow that pattern and avoid sprawl.

Keep the top readable in one screen. Test each move against branching: inline
what every branch needs and put the rest behind a precise pointer.

## Contracts, leading words, and language

Write positive, checkable contracts for what must hold rather than open-ended
prohibition lists. Keep prohibitions only as hard guardrails with a concrete
replacement behaviour, such as never changing a deployed Flyway migration.
Negation can make an unwanted pattern more available in context, so describe the
target state positively where a hard guardrail is unnecessary.

A leading word is a compact, pretrained term that anchors execution and
invocation, such as *tracer bullet*, *idempotent*, *vertical slice*, *grilling*,
*tight*, or *red/green*. Use one when it replaces repetition, not as decoration.
Follow the configured repository language policy rather than embedding a local
language boundary in a portable skill.

A good leading word guides both execution in the body and invocation in the
description: it connects team prompts, code, and docs to the same expected behaviour.

## Concretise and prune

Give one meaning one source of truth. Remove complete no-op sentences that do
not change model default behaviour, rather than merely deleting words. Prefer
the configured repository paths, commands, and types over placeholders.

## When to split

Split by invocation when a piece has its own leading word or trigger, or another
skill must reach it. Split by sequence when later steps tempt Copilot to rush the
current step. Each new skill has an always-loaded description cost, so tighten
the local completion criterion before splitting.
