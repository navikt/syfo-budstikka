---
name: to-spec
description: Use only after the user explicitly selects To Spec to turn an already resolved conversation or design into a concise engineering specification; do not interview or reopen decisions.
---

# To Spec

Synthesize what is already resolved into one durable engineering specification.
Do not run a new interview, invent missing choices, or create a spec when a
single actionable issue already carries the work.

Start only after the user has explicitly selected `/to-spec` in the current
conversation. Relevance or a prior recommendation is not selection.

Read the repository's issue-tracker adapter before tracker use. Follow its
language, terminology, label, and write-authorization rules.

## 1. Establish the source

Use the current conversation, the active issue or plan, relevant maintained
domain documentation, and only the decisions that constrain this work. Explore
the repository when needed to verify the current state and existing test seams.

Stop and return to the planning conversation when the problem, outcome, scope,
or a material user-owned choice is unresolved.

## 2. Draft the specification

Prefer the highest existing test seam and the fewest seams that can prove the
outcome. Write only what implementation and review need:

```markdown
## Problem

<the problem from the affected user's or system's perspective>

## Outcome

<observable result>

## Acceptance criteria

- [ ] <externally verifiable behavior>

## Locked decisions

- <decision and the constraint it creates>

## Proof

- <test seam and required evidence>

## Non-goals

- <explicit boundary>

## Dependencies

- <team, system, or ticket dependency, or none>
```

Include user stories only when they clarify genuinely different actors or
behaviors. Avoid file inventories, repeated decision rationale, speculative
future work, and exhaustive implementation prose. Inline a small schema, type,
or state-machine fragment only when it carries a decision more precisely than
prose.

## 3. Confirm and publish

Present the complete draft and proposed tracker metadata. Publish only after
explicit human authorization. Apply only labels and relationships defined by
the tracker adapter, then read the published issue back and verify it.

A specification does not imply ticket decomposition. Recommend `/to-tickets`
only when several independently deliverable slices need durable tracking.
