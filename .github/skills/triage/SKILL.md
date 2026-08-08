---
name: triage
description: "Use when incoming issues/bug reports in this repository need assessment and preparation: classify, verify the claim, clarify, prioritize, make AFK-ready. Triggers: 'triage', 'go through the inbox' / 'gå gjennom innboksen', 'look at #42' / 'se på #42', 'is this bug real' / 'er denne buggen ekte'. Not for breaking a finished plan into issues (see /to-issues)."
---

# triage

Move incoming issues (and external PRs) in this repository through a small state machine: **classify → verify → grill when needed → write a work-ready brief**. The focus here is **assessment and preparation**, not creation.

This complements two neighboring skills — do not duplicate them:
- `/to-issues` breaks a *finished plan* into new vertical slices. Triage takes *incoming* items and decides whether/how they should be worked.
- `/issue-management` owns the GitHub mechanics themselves once the item has been shaped. Repository, label and project facts come from `docs/agents/issue-tracker.md`; do not repeat the mechanics here.

A **PR is an issue with code**: the same roles, the same states. Where something differs for a PR it is marked "for a PR" below. A bare `#42` is looked up as either an issue or a PR.

Every comment you post during triage **must** start with:

```
> *Generert av AI under triage.*
```

## Reference documents

- [AGENT-BRIEF.md](AGENT-BRIEF.md) — how to write a durable, work-ready brief

## Roles

Two **category** roles (mapping to GitHub issue type, see `/issue-management`):

- `bug` — something is broken (issue type `Bug`)
- `enhancement` — new functionality or an improvement (issue type `Feature`/`Story`/`Task`)

Five **state** roles (GitHub labels):

- `needs-triage` — needs assessment
- `needs-info` — waiting for more information from the reporter
- `ready-for-agent` — fully specified, ready for an AFK agent
- `ready-for-human` — requires human implementation (judgment, external access, design choices, manual testing)
- `wontfix` — will not be done

For a PR the states are read against the attached code: `ready-for-agent` means a brief is attached and an agent takes the next step on the diff; `ready-for-human` means ready for a human to merge.

Every triaged item must have **one** category role and **one** state role. If the states conflict, flag it and ask before doing anything else. The actual label strings in the repository may differ from the canonical names above — check the existing labels.

**State transitions:** an untriaged item normally goes to `needs-triage` first; from there to `needs-info`, `ready-for-agent`, `ready-for-human` or `wontfix`. `needs-info` returns to `needs-triage` when the reporter replies. Transitions that look unusual: flag and ask first.

## Invocation

The user invokes `/triage` and describes things in natural language. Interpret and act. Examples:

- "Show me everything that needs attention"
- "Let's look at #42" (issue or PR)
- "Move #42 to ready-for-agent"
- "What is ready for agents to pick up?"

## Show what needs attention

Query the issue tracker and present three buckets, oldest first:

1. **Untriaged** — never assessed.
2. **`needs-triage`** — assessment in progress.
3. **`needs-info` with reporter activity since the last triage note** — needs re-assessment.

Include external PRs in the buckets and mark each line `[PR]` or `[issue]`. Discovery shows only *external* PRs (a colleague's in-progress PR is not triage work) — but an explicitly named PR is always triaged regardless of author. Show counts and a one-line summary per item. Let the user choose.

## Triage a specific item

### 1. Gather context
Read the whole item (body, comments, labels, author, dates; for a PR the diff as well). Parse earlier triage notes so you do not ask about what has already been settled. Explore the codebase:

- Follow the repository policy for documentation and load only sources that are
  relevant to the item.
- Run two checks: **(a) redundancy** — search for an existing implementation
  of the requested behavior *by domain term* (not just the wording of the
  report), and report where you looked. If it exists → already-implemented
  `wontfix` (step 5). **(b) previously rejected** — search for the concept in
  closed GitHub issues and `wontfix` items. Read the closing discussion and
  treat the item as previously rejected only when the rationale actually says
  so; a delivered or duplicated item is prior work, not a rejection.

### 2. Recommend
State your category and state recommendation with a rationale, plus a short codebase summary relevant to the item — including whether it is already implemented. Wait for direction.

### 3. Verify the claim
Before any grilling: check that the claim holds.
- **Bug** — reproduce from the steps in the report. Use `/diagnosing-bugs` to build a tight red signal (`./gradlew test`, a failing route call, a Kafka message that is not consumed idempotently, a Flyway error). If the symptom is a runtime/NAIS problem (pod crash/OOMKilled, 401/403, consumer lag, DB timeout), follow the diagnostic tree there.
- **PR** — confirm that the diff does what it claims: check out the branch, run `./gradlew test` and the relevant commands.
- Report: confirmed (with the code path), failed, or insufficient detail (a strong `needs-info` signal). A confirmed verification makes for a much stronger brief.

### 4. Grill (when needed)
If the item needs more substance, run `/grilling` and grill it into shape one
question at a time. When clarified concepts or lasting decisions ought to be
documented, recommend the documented route, explain why and wait for the user's
choice. Use `/architecture-review` for NAV-specific findings; once the
documented route has been chosen, `/domain-modeling` is used to update the
glossary or write a qualifying ADR under the repository policy.

### 5. Apply the outcome
- `ready-for-agent` — post a work-ready brief ([AGENT-BRIEF.md](AGENT-BRIEF.md)). The item now counts as pickable in @grillmester's phase loop.
- `ready-for-human` — the same structure as an agent brief, but note *why* it cannot be delegated.
- `needs-info` — post a triage note (template below).
- `wontfix` — close, with a comment depending on *why*:
  - **Already implemented** — the change already exists. Point to where it lives in the code.
  - **Rejected (bug)** — a polite explanation, then close.
  - **Rejected (enhancement)** — write a self-contained rationale in the
    closing comment, then close. The closed issue is the history; do not create
    a separate decision archive in the repository.
- `needs-triage` — set the role. An optional comment if there is partial progress.

## Quick state override

If the user says "move #42 to ready-for-agent", trust it and set the role directly. Confirm what you are about to do (role changes, comment, closing), then act. Skip grilling. If you move to `ready-for-agent` without a grilling session, ask whether they want a brief written.

## Template for needs-info

```markdown
> *Generert av AI under triage.*

## Triage-notat

**Det vi har etablert så langt:**

- punkt 1
- punkt 2

**Dette trenger vi fortsatt fra deg (@melder):**

- spørsmål 1
- spørsmål 2
```

Capture everything that was settled during grilling under "etablert så langt" so the work is not lost. Questions must be concrete and actionable — not "please provide more information".

## Resume an earlier session

If there are earlier triage notes on the item, read them, check whether the reporter has answered outstanding questions, and present an updated picture before continuing. Do not ask about what has already been settled.
