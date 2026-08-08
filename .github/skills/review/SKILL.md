---
name: review
description: "Use when the active task's diff needs a self-review before a fresh grill-inspektor review or a PR: uncommitted changes, a branch against main, or the diff since a fixed point is examined for correctness, regression, edge cases and scope. Triggered after implementation, or when someone says 'self-review', 'go through my own diff', 'review before PR', 'review since X'."
---

# Self-review — go through the task diff before other eyes

Examine the active task's change in the verification phase, before the PR and
any `grill-inspektor` review. The active writer fixes findings; the orchestrator
reports a scoped correction back to the writer.

## Read the diff, not your memory

Trace changed behavior from the text and the call flow, not from what you
remember you set out to achieve. A green test run only proves that the tests you
wrote pass; self-review looks for what they do not cover.

## 1. Pin the scope and fetch the whole diff

Start with `git status --short`. For uncommitted work, use the task brief's file
scope, `git diff` for tracked files and the full text for new untracked files.
For a branch, use the fixed point the user gave — commit SHA, branch, tag,
`main`, `HEAD~5` — and include any working-directory diff on top. Verify that
the fixed point resolves and that the combined task diff is not empty:

```bash
git rev-parse <fixed-point>                # does the reference resolve?
git diff <fixed-point>...HEAD --stat       # three-dot = against the merge base
git log <fixed-point>..HEAD --oneline      # commits in scope
git status --short                         # uncommitted and untracked files
```

Use the **three-dot** form (`...HEAD`) so you compare against the common
ancestor, not against a branch that has drifted on. A plain `git diff` leaves
out untracked files; read them in full. Stay within one task scope through the
whole review.

## 2. Six axes — keep them separate

Go through the diff along six axes. **Do not merge them.** A change can pass one axis and fail another (correct code implementing the wrong requirement; the right requirement implemented against the repository's conventions). If you merge them, one masks the other.

### A. Correctness
Does each changed line do what it appears to do? Read the control flow, not the comments. Off-by-one, inverted condition, wrong variable, forgotten `return`, a suspending call without `await`/a coroutine scope, `runBlocking` in a hot path, a resource that is not closed (`use {}`).

### B. Regression
What ELSE does the changed code touch? Follow callers upward: did you change a shared function, a route handler, a serialization model, a DTO that another consumer also uses? A changed signature or default value that silently changes behavior for existing callers? Check whether an existing test should have caught the change — if it did not, coverage is missing.

### C. Edge cases
Empty list, `null`, missing field, concurrency, retry, timeout. NAV/Ktor-specific — see the checklist below.

### D. Scope (diff disproportion)
Is there anything in the diff the task did NOT ask for? Refactoring smuggled into a bug fix, unrelated formatting, a "while I was here" change. Every hunk must be traceable to the task/issue or the active plan. And the reverse: did the task ask for something that is not in the diff?

### E. Requirement coverage (against the spec)
Compare the diff against the task/issue, the active plan or the task brief, and
the relevant maintained documentation. For each requirement: met / partial /
missing. Follow the repository policy's status interpretation for the relevant
ADRs. A silent deviation from an accepted decision is a blocker; a proposed
decision binds only when the active task explicitly builds on it.

### F. Standards coverage (against the repository's conventions)
Does the code follow the way this repository writes code — Ktor route structure, the error contract via StatusPages, the DI pattern, naming, package structure under `no.nav.syfo`? Skip anything tooling enforces (formatting, import order) — that is caught by the gates, not by your eyes.

## NAV/Ktor edge cases (axis C, checklist)

- **Auth:** Missing or wrong claim check? Is `azp` validated against `AZURE_APP_PRE_AUTHORIZED_APPS`? Is NAVident/`pid` read safely (not `!!` on a claim that may be absent)? Is the route behind the right `authenticate("…")` branch?
- **PII in logs:** Do national identity numbers, names, diagnosis or sykmelding status sneak into a standard log line via string interpolation? Displaying personal data to an employee → CEF audit log, not the standard log. (Details: `/security-review`.)
- **Kafka:** Empty/`null` record value? Idempotence on redelivery? Manual vs. auto-commit semantics? Poison message — does it end up in a DLQ or loop? Is the offset committed before or after the side effect?
- **Postgres/Flyway:** New migration — runnable forward AND backward-compatible with running pods (rolling deploy)? A `NOT NULL` column without a default on a non-empty table? An N+1 introduced? Connection closed / returned to the pool? A transaction boundary around multi-step writes?
- **Ktor HTTP:** Are errors mapped to the right status via StatusPages (no stack trace leaked to the client)? Is `Nav-Call-Id` propagated to outgoing calls? Timeout and retry on external calls? Pagination preserved?
- **NAIS:** Is a changed `accessPolicy.inbound` mirrored in the auth code, and vice versa? Is `outbound` pointed at the right cluster/namespace? Is a new env variable actually set in the manifest?
- **Coroutines:** A blocking call inside a suspend function? Missing `CoroutineScope`/structured concurrency? An exception swallowed in a launch?

## 3. Fix, then hand over to fresh eyes

Self-review produces an action, not a report for the archive:

1. **Findings within the task:** If you are the active writer, fix them before
   you return. If you are the orchestrator after Kokk, do not edit code; send
   the smallest scoped correction back to Kokk.
2. **Rerun the deterministic gates** after fixing — `./gradlew test`
   (and `build`/lint where they exist). Hard pass/fail, with fresh evidence in
   the same message. No "looks good" without command + output + exit code.
3. **Findings you deliberately leave** (out of scope, their own task) → note
   them briefly in the active task so they do not get lost.
4. **Decision candidates** that surface during the review → report them.
   When a candidate passes the ADR gate, recommend the documented route and wait
   for the user's choice before `/domain-modeling` writes.

Once the steps are complete, report that the diff is ready for any
`grill-inspektor` review. Grillmester handles the risk assessment,
the user's choice and the review flow.

## Flow coupling

- **Phase in the phase loop:** verify (phase 5), before any inspector review.
- **Reads:** the task/issue, the active plan or task brief, and the sources the
  repository policy makes relevant. Read a task-local `.grill/` only when the
  calling workflow has chosen it.
- **Depth:** use the relevant domain skill when a finding requires specialized review.
