# IMPLEMENTATION_BRIEF v1

The brief is the task-scoped Grillmester–Kokk contract. It is not a global task
log or a security artifact. Its source may be the conversation, an issue, or a
pull request, but delegation uses one complete brief either inline or at one
explicitly named task-scoped path.

```text
IMPLEMENTATION_BRIEF v1
id: <stable issue, pull-request id, or slug>
base_sha: <full commit SHA that HEAD must match>
goal: <one sentence describing the observable effect>
scope:
  - <repository path — allowed change>
  - <create: new/path — allowed creation>
non_goals:
  - <explicitly outside this slice>
acceptance:
  - <testable done criterion>
locked_decisions:
  - <ref: existing ADR/doc or docs/decisions.md#Bnn>
  - <choice: explicit choice Kokk must not reopen>
verification:
  - <concrete command> — <expected evidence>
risk: R0|R1|R2|R3|R4 — <reason>
commit_policy: atomic-local|none
```

One brief describes exactly one independently testable vertical slice. Scope
must name existing paths or mark creations explicitly. Unresolved decisions,
vague acceptance, or verification without a concrete command make the brief
incomplete.

## Risk classification

Choose the level by consequence and uncertainty, not line count. When in doubt,
use the higher level.

| Level | Characteristics |
|---|---|
| **R0 trivial** | Text or documentation with no runtime effect. |
| **R1 small** | Clear, reversible change in a few files with no red flags. |
| **R2 medium** | Bounded runtime change with a known solution and no red flags. |
| **R3 high or unclear** | Unclear solution, several domains, hidden edge cases, or a red flag. |
| **R4 critical** | Auth/access, PII/secrets, schema/data movement, Kafka/API contracts, deployment/GitHub Actions security, or a hard-to-reverse architecture change. |

Red flags include security or privacy consequences, durable data loss,
backward compatibility, distributed consistency, production impact, unknown
blast radius, and decisions that cannot be reversed cheaply. Small diffs do
not downgrade risk.

## Preflight

Before delegation:

- capture the full current `HEAD` as `base_sha`
- inspect staged, unstaged, untracked, conflict, and submodule state
- preserve existing work by waiting or using a separate worktree
- confirm every referenced decision and scoped path
- confirm acceptance is testable and verification has expected evidence

Kokk repeats these checks before editing. A mismatch returns `NEEDS_CONTEXT`;
neither agent discards or absorbs unrelated work.

## Result

Kokk returns:

```text
KOKK_RESULT
status: DONE|DONE_WITH_CONCERNS|NEEDS_CONTEXT|NEEDS_DECISION|BLOCKED
brief_id: <id>
summary: <short>
changed_files: <list or none>
verification:
  - command: <command>
    result: <short relevant output>
    exit_code: <code>
concerns_or_blockers: <list or none>
needed: <missing fact, decision, or blocker; otherwise none>
```

`DONE` requires satisfied acceptance and fresh verification.
`DONE_WITH_CONCERNS` names a non-blocking concern. Missing facts return
`NEEDS_CONTEXT`; user-owned choices return `NEEDS_DECISION`; external or
technical blockers return `BLOCKED`.

## Review

Grill-inspektor reviews every completed R3/R4 slice. R0–R2 review is opt-in and
is offered only for material work, reported concerns, or an explicit user
request. Review input is deliberately simple:

- the complete brief and Kokk result, or Barista's equivalent request
- the full baseline SHA
- one complete baseline-to-worktree patch, including new files
- fresh verification evidence

The caller creates the temporary patch outside the repository and checks the
same `HEAD`, status, and diff after review. A changed boundary makes the review
stale. A very large or mixed diff should be split into coherent work instead
of hidden behind custom manifests or digest protocols.

When several slices form one delivery, reassess aggregate risk and
interactions. Aggregate R3/R4 requires an integrated final review. A single
slice never needs duplicate review.

## Commit and delivery boundaries

`none` means no commit. `atomic-local` permits Kokk to create at most one local
commit containing only its own in-scope files. Kokk never pushes, opens or
updates a pull request, merges, amends, rebases, or resets.

Fresh evidence belongs in `KOKK_RESULT` and, at delivery, the pull-request
description. Durable decisions belong in ADRs, the glossary, or the decision
register. Review approval does not grant Git or GitHub delivery authority.
