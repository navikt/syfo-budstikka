---
name: write-a-skill
description: "Create or improve a repository skill with deliberate invocation and progressive disclosure."
disable-model-invocation: true
---

# Write a skill

Create or improve one repository skill. Read the repository's configured
language, skill-layout, and validation guidance before editing.

## Frontmatter and invocation

Choose invocation before writing the description:

- For a model-invoked skill, front-load its leading word or action, then state
  `Use when ...` with one distinct natural-language trigger per branch.
- For a user-invoked skill, set `disable-model-invocation: true` and write a
  short human-facing summary. Do not spend its description on model trigger
  phrases or repeat the `/name` already shown by the picker.

`name` is the kebab-case folder and slash-command name. Use
`disable-model-invocation`, `user-invocable`, `allowed-tools`, `argument-hint`,
and `license` only when they change Copilot behaviour or distribution. Copilot
surfaces project descriptions in its catalog; budget the complete catalog as a
conservative upper bound and measure the model-invokable subset separately.
Read [references/authoring-principles.md](references/authoring-principles.md)
for official fields, description examples, and invocation trade-offs.

## Information hierarchy

Keep sequence and checkable completion criteria in `SKILL.md`. Move heavy
reference material, long examples, tables, and edge-case catalogs to
`references/<name>.md`, with a pointer stating when and why to read it. Give
each rule one source of truth. Read
[references/authoring-principles.md](references/authoring-principles.md) for the
full hierarchy, contract pattern, leading words, language boundary, and pruning.

## Workflow

1. **Clarify one job and its invocation.** If the job cannot be stated in one
   sentence, clarify or split it before authoring.
2. **Write the description first.** For model invocation, list real signals
   before the body and sharpen the boundary against existing skills. For manual
   invocation, write only the short picker summary.
3. **Place content.** Keep universal sequence and completion criteria in
   `SKILL.md`; move branch-specific detail behind a precise context pointer.
4. **Make completion checkable.** End each step in an observable condition and
   state the evidence the skill must return.
5. **Prune.** Remove no-ops and duplication; move living but rare material to a
   precisely linked reference.
6. **Verify.** Read the description as the model would see it, run the
   repository's documented validator, and report the result.

## Split only for a better trigger or sequence

Each new skill adds its description to catalog context. Split by invocation when
a piece has its own leading word or other skills must reach it; split by sequence
when later steps cause premature completion of earlier ones. Tighten the
completion criterion before splitting. Read
[references/vocabulary-and-failure-modes.md](references/vocabulary-and-failure-modes.md) for
premature-completion, duplication, sediment, sprawl, and no-op diagnosis.
