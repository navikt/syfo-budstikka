---
name: create-a-skill
description: Create, revise, review, or diagnose an agent skill with a predictable authoring and forward-testing workflow. Use when a user asks to create or improve a skill, investigate why one does not trigger, or validate skill behavior.
argument-hint: "[goal or existing skill path]"
---

# Create a Skill

Create, revise, or diagnose one skill. The target repository's instructions and
runtime contract govern the artifact this skill produces.

## Choose the mode

- **Create or revise** — run the complete workflow and edit the skill, callers,
  documentation, and provenance that the change actually affects.
- **Diagnose or review** — inspect, design, and validate read-only, then report
  evidence and concrete recommendations. Make no edits unless the user also
  asks for implementation.

When a creation request turns out to be owned by an existing skill or
reference, stop with a recommendation to extend that owner. Continue into an
edit only when the user requested implementation.

## 1. Inspect the target

Read the target repository's agent instructions, skill policy, existing target
skill, neighboring skills, and known callers. Identify the target runtime and
its available structural or discovery validators.

For a revision, inspect enough history and usage to distinguish intentional
behavior from sediment. For a new skill, search for an existing skill or
reference that already owns the job.

Complete this step when you can state:

- the skill's one job;
- its boundary against neighboring skills;
- the target runtime and repository policies;
- the current callers or intended usage.

## 2. Design the invocation boundary

Decide whether the skill is manual-only, model-only, or reachable through both
surfaces before writing the body. For every model-reachable branch, write
representative positive prompts and nearby prompts that should not select it.
For every human-reachable branch, define the explicit slash invocation and any
argument shape.

When targeting GitHub Copilot, load
[the GitHub Copilot adapter](references/github-copilot.md) for supported paths,
frontmatter, and validation. For another runtime, use its authoritative local
contract instead.

Complete this step when the invocation mode, its reachable surfaces, the
relevant prompts or explicit commands, and the human- or model-facing
description are explicit.

## 3. Design the information hierarchy

Apply [the authoring principles](references/principles.md) to decide what stays
in `SKILL.md`, what is disclosed behind a contextual pointer, and whether
deterministic repeated work belongs in `scripts/`. When a defined term needs
clarification or a failure mode needs diagnosis, find and read only its heading
in [the glossary](references/glossary.md).

Keep repository and stack constraints in repository instructions or a
repository-specific reference unless they are intrinsic to the skill's single
job. Reuse existing templates, scripts, and assets when they already express
the contract.

Complete this step when every proposed file has a purpose and every disclosed
file has a pointer whose wording says when to load it.

## 4. Implement and reconnect

For create or revise mode, write a concise `SKILL.md` with supported
frontmatter, ordered steps or co-located reference, and checkable completion
criteria. Add only the bundled resources justified in the previous step.

Update direct callers, routers, documentation, and provenance when the skill's
name, invocation boundary, or imported material changes. Follow the target
repository's artifact language policy.

Complete this step when there are no stale names, competing owners, dangling
links, or undocumented imported sources. In diagnose or review mode, skip the
edits and report these conditions as findings instead.

## 5. Validate and forward-test

Run safe structural, link, and discovery checks. In create or revise mode,
execute bundled scripts on representative fixtures and inspect the final diff
for scope, relevance, no-ops, duplication, and stale repository assumptions.
In diagnose or review mode, run only non-mutating checks and include successes
and failures as evidence instead of requiring the target to pass.

Forward-test in a fresh session or subagent that receives only the target
artifact, its actual runtime/repository contract, and a realistic request. Keep
the expected answer, suspected defect, and planned fix out of the test prompt.
Use a temporary fixture, isolated test repository, dry-run, mock, or other
non-production surface. A real execution that writes external state, deploys,
migrates, sends messages, or can destroy data requires the same explicit
authority as normal product work.

Match the tests to invocation mode:

- **Manual-only** — verify picker visibility, absence of autonomous selection,
  and one safe explicit slash execution per distinct branch.
- **Model-only** — verify positive and close-negative autonomous selection,
  absence from the picker, and one safe representative execution per branch.
- **Both** — verify both surfaces, positive and close-negative autonomous
  selection, and one safe representative execution per branch.

In create or revise mode, revise against observed behavior. The edit is
complete when structure and links pass, invocation matches its mode, every
branch meets its completion criterion, and the final diff contains only
intentional changes.

In diagnose or review mode, the work is complete when an evidence-based report
covers the observed invocation behavior, structural or behavioral failures,
affected branches, and concrete recommendations. A failing target is a valid
diagnosis result.
