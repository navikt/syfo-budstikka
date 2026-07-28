---
name: diagnosing-bugs
description: "Diagnose bugs and performance regressions systematically. Use when code fails, throws, hangs, runs slowly, produces flaky tests, or shows NAIS runtime symptoms."
---

# Systematic bug diagnosis

Use this discipline for difficult faults. Skip a phase only with an explicit
reason. Read relevant `docs/context.md` and ADRs, and keep task-scoped evidence
through non-trivial fixes. For runtime/platform symptoms, first use the matching
`/nav-troubleshoot` tree; see
[references/runtime-symptoms.md](references/runtime-symptoms.md).

## 1. Build a feedback loop

Before theory or code reading, build a tight, red-capable pass/fail loop for the
exact symptom. See [references/loop-catalog.md](references/loop-catalog.md) for
prioritized loop types and Ktor/Gradle examples.

Phase 1 finishes only when one already-run command, with output, is fast,
agent-runnable, deterministic (or has documented high reproduction rate), and
fails for the actual fault. Otherwise stop, explain attempts, and ask for
environment access, a captured artifact, or permission for temporary
instrumentation. See [references/phase-guidance.md](references/phase-guidance.md)
for tightening, flakiness, and the complete contract.

## 2. Reproduce and minimize

Run the loop until it fails for the user's exact symptom. Remove input, callers,
configuration, data, and steps one at a time until each remaining element is
necessary. The minimal reproduction drives hypotheses and regression testing.

## 3. Hypothesize

Generate 3–5 ranked, falsifiable hypotheses before testing any. For each:

> If <X> is the cause, changing <Y> will remove the fault / changing <Z> will
> make it worse.

Show the ranking to the user first, but do not block if they are away.

## 4. Instrument

Each probe tests one prediction and changes one variable. Prefer debugger/REPL,
then targeted logs at the boundary that distinguishes hypotheses. Tag temporary
logs `[DEBUG-...]` and remove them. Never log FNR, tokens, names, or special
category data; use technical identifiers and `Nav-Call-Id`/`callId`. For
performance, measure a baseline and profile before changing code.

## 5. Fix and regress

Turn the minimal reproduction into a failing test only at a seam that reaches the
real fault pattern. Without such a seam, report an architecture finding rather
than false coverage. See the test fail, apply the fix, see it pass, then rerun
the original scenario. Return fresh command, output, and exit-code evidence.

## 6. Clean up and learn

Rerun the original reproduction, confirm the regression test or document the
missing seam, remove debug instrumentation/harnesses, and record the correct
hypothesis plus fresh quality gates in commit/PR. See
[references/phase-guidance.md](references/phase-guidance.md) for the full list
and ADR escalation.

Related model-invokable guidance: `/auth-overview`. When a diagnosis exposes a
durable decision or cross-cutting architecture choice, recommend the manual
Grill with docs or Nav architecture review workflow and wait for the user to
select it; never invoke either automatically.
