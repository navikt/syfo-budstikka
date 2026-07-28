---
name: tdd
description: "Drive test-first development through red-green-refactor. Use when the user requests test-first work or a regression test before a bug fix; use focused test skills for ordinary test writing."
---

# Test-driven development (Ktor / no.nav.budstikka)

## Philosophy

**Core principle:** Verify behaviour through public interfaces, not implementation
details. Code may change completely; the tests should survive.

**Good tests** use real code paths through public APIs. In a Ktor backend, that
normally means entering through HTTP with `testApplication`/`client`, or calling
a domain service through its public function. A good test reads like a
specification — `` `bruker uten gyldig token får 401` `` states exactly which
capability exists. These tests survive refactoring because they ignore internal structure.

**Bad tests** couple to implementation: they mock internal collaborators, test
private functions, or verify around the service (for example a direct PostgreSQL
SELECT instead of reading through the service). A warning sign is a test failing
after refactoring with unchanged behaviour. If renaming an internal function
breaks tests, they tested implementation rather than behaviour.

Read [references/tests.md](references/tests.md) for examples and
[references/mocking.md](references/mocking.md) for when and how to mock system boundaries.

## Anti-pattern: horizontal slices

**Do not write every test before all implementation.** That is horizontal slicing:
treating RED as “write all tests” and GREEN as “write all code”.

It produces weak tests:

- Bulk-written tests verify imagined, not actual, behaviour.
- They test the *shape* of data structures and function signatures instead of
  user-facing behaviour.
- They become insensitive to real change: pass when behaviour breaks and fail
  when everything is correct.
- They commit to test structure before the implementation is understood.

```
WRONG (horizontal):
  RED:   test1, test2, test3, test4, test5
  GREEN: impl1, impl2, impl3, impl4, impl5

RIGHT (vertical):
  RED→GREEN: test1→impl1
  RED→GREEN: test2→impl2
  RED→GREEN: test3→impl3
```

Use vertical tracer-bullet slices: one test → one implementation → repeat. Each
test answers what the preceding cycle just taught you.

## Workflow

### 1. Plan

Read `docs/context.md` when it exists so test names and interface vocabulary use
the domain language. Respect decisions in `docs/adr/` for the affected area. If
following a plan from `@grillmester`, stay within the task-scoped brief and mark
behaviours there.

When the current agent is Kokk, the validated `IMPLEMENTATION_BRIEF v1`
supplies the confirmed interface, behaviours, and locked choices. Never ask the
user or reopen them. If the brief lacks a necessary repository fact, return
`NEEDS_CONTEXT`; if it lacks a product or interface choice, return
`NEEDS_DECISION` without editing. The interactive clarification steps below
apply only outside delegated Kokk mode.

Before writing code:

- [ ] Clarify needed interface changes with the user, or verify them in Kokk's brief.
- [ ] Clarify and prioritise behaviours, or verify the brief already locks them.
- [ ] Look for deep modules — small interface, deep implementation — so the
  service stays testable from outside.
- [ ] List behaviours to test, not implementation steps.
- [ ] Obtain user confirmation, or treat the validated brief as confirmation.

Outside delegated Kokk mode, ask: “Which public interface should we expose?
Which behaviours matter most to test?”

**You cannot test everything.** Clarify the behaviours that matter most. Spend
test effort on critical paths and complex logic — authorisation, validation
rules, state transitions — not every conceivable edge case.

### 2. Tracer bullet

Write **one** test proving **one** thing end-to-end through the public interface:

```
RED:   Write a test for the first behaviour → it fails
GREEN: Minimal code to pass → it passes
```

For a new endpoint, a tracer bullet is typically a `testApplication` test that
hits the route and expects the right status. It proves routing, module setup,
and response work together.

### 3. Incremental loop

For each remaining behaviour:

```
RED:   Next test → it fails
GREEN: Minimal code → it passes
```

Rules:

- One test at a time.
- Write only enough code to pass the current test.
- Do not predict future tests.
- Keep tests on observable behaviour.

Run a fast, focused check while working:

```bash
./gradlew test --tests "no.nav.budstikka.<KlasseNavn>"
echo "exit: $?"
```

A GREEN claim requires fresh evidence in the same response: command, output, and
exit code. Without it, report `UNVERIFIED`.

### 4. Refactor

Once every test passes, review [refactoring candidates](references/refactoring.md):

- [ ] Extract duplication.
- [ ] Deepen modules by moving complexity behind simple interfaces.
- [ ] Use SOLID where it fits naturally.
- [ ] Consider what new code reveals about existing code.
- [ ] Run tests after each refactoring step.

**Never refactor while RED.** Reach GREEN first.

Once implementation is complete and green, return what was actually verified
(the command, result, and exit code) so the `@grillmester` loop can close the phase.

## Checklist per cycle

```
[ ] Test describes behaviour, not implementation
[ ] Test uses only a public interface
[ ] Test would survive internal refactoring
[ ] Code is minimal for this test
[ ] No speculative features were added
[ ] GREEN is proved by fresh command + output + exit code in the same response (otherwise UNVERIFIED)
```

## Bug fixing is TDD

Start a bug fix with a **reproduction test**: write a test that fails because the
bug exists (RED), then fix it until green (GREEN). This proves the defect and
prevents regression. Never write the fix first.

For a hard-to-reproduce bug (flaky, timing-dependent, or environment-dependent),
start with `/diagnosing-bugs` to establish a tight, red-capable reproduction loop;
the reproduction test here is then the minimised loop.
