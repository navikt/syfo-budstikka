---
name: diagnosing-bugs
description: "Use when a bug needs systematic diagnosis — something throws, fails, hangs or is slow in test or production, a flaky test, a performance regression, or a runtime symptom on NAIS (pod crash/OOMKilled, 401/403, Kafka consumer lag, DB timeout, Flyway error). Or when someone says 'debug this', 'diagnose', 'why is X failing'. NOT for designing new functionality (use /grilling and choose the documented route when needed)."
---

# Diagnosing Bugs

A discipline for hard bugs. Skip phases only when you can explicitly justify it.

Follow the narrow load order in `docs/agents/domain.md`: read only the topic
documents and ADRs that touch the symptom, and the glossary when domain language
is relevant. For non-trivial fixes, use the active task or task brief as your
scope; a task-local `.grill/` is used only when the calling workflow has chosen
it.

Is the symptom a **runtime/platform problem** (the app runs, but fails in production) — start in the symptom table at the bottom and follow the diagnostic tree in `/nav-troubleshoot` (which owns the trees), then come back here for the fix discipline.

## Phase 1 — Build a feedback loop

**This is the skill itself.** Everything else is mechanics. If you have a **tight** pass/fail signal for the bug — one that goes red on _this_ bug — you will find the cause; bisection, hypothesis testing and instrumentation merely consume the loop. Without it, no amount of code reading will save you.

Spend disproportionate effort here. **Be aggressive. Be creative. Do not give up.**

### Ways to construct one — try them roughly in this order

1. **Failing test** at the seam that reaches the bug — unit test, integration test, or Ktor `testApplication { }`:
   ```bash
   ./gradlew test --tests "no.nav.syfo.<class>.<method>"
   ```
   ```kotlin
   testApplication {
       application { module() }
       val res = client.get("/api/sykmelding/123")
       assertEquals(HttpStatusCode.OK, res.status) // goes red on exactly this bug
   }
   ```
2. **curl / HTTP script** against a running local Ktor server (`./gradlew run`), diffing status/body against known-good.
3. **Replay of a captured event.** Save a real Kafka record / HTTP payload / event log to disk and play it through the code path in isolation (call the consumer handler / route handler directly with the payload).
4. **Throwaway harness.** Spin up a minimal subset (one route, mocked dependencies via MockK/WireMock, in-memory Postgres via Testcontainers) that hits the failing code path with a single function call.
5. **Property / fuzz loop.** If the bug is "sometimes wrong output", run 1000 random inputs and look for the failure mode.
6. **Bisection harness.** If the bug appeared between two known states (commit, dataset, version), automate "boot at X, check, repeat" so you can `git bisect run` it.
7. **Differential loop.** Run the same input through the old vs. the new version (or two configurations) and diff the output.
8. **HITL bash script.** Last resort. If a human has to click/act, drive _them_ with `scripts/hitl-loop.template.sh` so the loop stays structured. Captured output is fed back to you.

Build the right feedback loop and the bug is 90% fixed.

### Tighten the loop

Treat the loop as a product. Once you have _a_ loop, **tighten** it:

- Can I make it faster? (Cache setup, skip unrelated init, `./gradlew test --tests` on a single test, reuse Testcontainers.)
- Can I make the signal sharper? (Assert on the specific symptom, not "did not crash".)
- Can I make it more deterministic? (Pin time with a `Clock`, seed the RNG, isolate the DB schema, freeze the network with WireMock/MockK.)

A 30-second flaky loop is barely better than no loop; a 2-second deterministic one is tight — a debugging superpower.

### Non-deterministic bugs

The goal is not a clean repro, but a **higher reproduction rate**. Loop the trigger 100×, parallelize, add stress, narrow timing windows, inject sleeps. A 50% flaky bug is debuggable; 1% is not — raise the rate until it is.

### When you genuinely cannot build a loop

Stop and say so explicitly. List what you tried. Ask the user for: (a) access to the environment that reproduces it (e.g. `dev-gcp`), (b) a captured artifact (HAR file, `kubectl logs --previous` dump, Kafka record, trace from Tempo), or (c) permission for temporary production instrumentation. Do **not** move on to hypotheses without a loop.

### Completion criterion — a tight loop that can go red

Phase 1 is done when the loop is **tight** and **red-capable**: you can name **one command** — a script path, a test invocation, a curl — that you have **already run at least once** (paste the invocation and its output), and that is:

- [ ] **Red-capable** — it drives the actual failing code path and asserts the user's **exact symptom**, so it can go red on this bug and green once fixed. Not "runs without failing" — it must be able to _catch this specific bug_.
- [ ] **Deterministic** — the same verdict every run (flaky bugs: a pinned, high reproduction rate, per above).
- [ ] **Fast** — seconds, not minutes.
- [ ] **Agent-runnable** — you can run it unsupervised; a human in the loop only via `scripts/hitl-loop.template.sh`.

If you catch yourself reading code to build a theory before this command exists, **stop — jumping straight to a hypothesis is exactly the mistake this skill prevents.** No red-capable command, no phase 2.

## Phase 2 — Reproduce + minimize

Run the loop. Watch it go red — the bug shows up.

Confirm:

- [ ] The loop produces the failure mode **the user** described — not some other bug that happens to be nearby. Wrong bug = wrong fix.
- [ ] The bug is reproducible across several runs (or, for non-deterministic bugs, reproducible at a high enough rate to debug against).
- [ ] You have captured the exact symptom (error message, wrong output, slow timing) so later phases can verify that the fix actually hits it.

### Minimize

Once it is red, shrink the repro to the **smallest scenario that still goes red**. Cut inputs, callers, config, data and steps **one at a time**, rerunning the loop after each cut — keep only what is load-bearing for the bug.

Why bother: a minimal repro shrinks the hypothesis space in phase 3 (fewer moving parts left to suspect) and becomes the clean regression test in phase 5.

Done when **every remaining element is load-bearing** — remove any one of them and the loop goes green.

Do not move on before you have reproduced **and** minimized.

## Phase 3 — Hypothesize

Generate **3–5 ranked hypotheses** before testing any of them. Single-hypothesis generation anchors on the first plausible idea.

Each hypothesis must be **falsifiable**: state the prediction it makes.

> Format: "If <X> is the cause, then <changing Y> will make the bug disappear / <changing Z> will make it worse."

If you cannot state the prediction, the hypothesis is a gut feeling — discard it or sharpen it.

**Show the ranked list to the user before testing.** They often have domain knowledge that re-ranks it instantly ("we just deployed a change to #3"), or know hypotheses they have already ruled out. Cheap checkpoint, big time saver. Do not block on it — proceed with your own ranking if the user is away.

## Phase 4 — Instrument

Each probe must map to a specific prediction from phase 3. **Change one variable at a time.**

Tool preference:

1. **Debugger / REPL inspection** if the environment supports it. One breakpoint beats ten log lines.
2. **Targeted logging** at the boundaries that separate the hypotheses. In Ktor: SLF4J/Logback via `LoggerFactory.getLogger(...)`.
3. Never "log everything and grep".

**Tag every debug log** with a unique prefix, e.g. `log.info("[DEBUG-a4f2] ...")`. Cleanup at the end becomes a single grep. Untagged logs survive; tagged logs die.

**PII boundary (NAV):** never log national identity numbers, tokens, names or special categories of personal data — not even in temporary debug logs. Log IDs/correlation (`Nav-Call-Id`, `callId`), not personal data.

**Perf branch.** For performance regressions, logs are usually the wrong tool. Instead: establish a baseline measurement (Micrometer timer, `measureTimedValue {}`, profiler, `EXPLAIN ANALYZE` on the query), then bisect. Measure first, fix afterwards. See `/nav-troubleshoot` (observability diagnosis) for Mimir/Loki/Tempo.

## Phase 5 — Fix + regression test

Write the regression test **before the fix** — but only if a **correct seam** exists for it.

A correct seam is one where the test hits the **real failure pattern** as it occurs at the call site. If the only available seam is too shallow (a single-caller test when the bug requires several callers, a unit test that cannot replicate the chain that triggered the bug), a regression test there gives false confidence.

**If no correct seam exists, that is itself the finding.** Note it. The architecture prevents the bug from being locked down. Flag it for the next phase.

If a correct seam exists:

1. Turn the minimized repro into a failing test at that seam.
2. Watch it fail.
3. Apply the fix.
4. Watch it pass.
5. Run the phase 1 loop against the original (un-minimized) scenario.

Pass/fail is decided deterministically with `./gradlew test` (and lint/build where relevant). No "looks right" claim without fresh evidence — command + output + exit code in the same message.

## Phase 6 — Cleanup + post-mortem

Required before you declare done:

- [ ] The original repro no longer reproduces (rerun the phase 1 loop)
- [ ] The regression test passes (or the absence of a seam is documented)
- [ ] All `[DEBUG-...]` instrumentation removed (`grep -rn "DEBUG-" src/`)
- [ ] Throwaway harness deleted (or moved to a clearly marked debug location)
- [ ] The hypothesis that turned out to be right is written in the commit/PR message — so the next debugger learns
- [ ] Fresh green evidence for the quality gates is returned to @grillmester's verify phase

**Then ask: what would have prevented this bug?** If the answer involves an
architectural change (no good test seam, entangled callers, hidden coupling),
carry the finding forward via `/grilling`. Use `/architecture-review` for
NAV-specific consequences. When lasting concepts or decisions ought to be
documented, recommend the documented route and wait for the user's choice before
`/domain-modeling` writes. Give the recommendation **after** the fix is in, not
before — you know more now than when you started.

## Symptom overview — runtime/platform

If the app fails in **production** (not in test), start in the right diagnostic tree, and come back here for the fix discipline (phases 5–6).

The diagnostic trees are owned by `/nav-troubleshoot` (not duplicated here). Follow the tree there, and come back here for the fix discipline (phases 5–6).

| Symptom | Tree in `/nav-troubleshoot` |
|---------|-----------|
| Pod does not start / crashes / OOMKilled / ImagePullBackOff | `references/pod-diagnose.md` |
| 401 Unauthorized / 403 Forbidden (TokenX / Azure AD / Texas) | `references/auth-diagnose.md` |
| Kafka consumer lag / messages not processed | `references/kafka-diagnose.md` |
| DB connection errors / HikariCP pool exhaustion / Flyway errors | `references/database-diagnose.md` |
| Error rate/latency/restarts where the signals diverge | `references/observability-diagnose.md` |

The diagnostic trees are NAV/Ktor-specific. Generic Kubernetes/Kafka/SQL knowledge is not replicated — bring that from your own repertoire. Always propose the **least invasive fix first**; escalate only when needed. Changing production config, restarting a pod or changing pool size in prod: **ask first**.

## Related skills

- `/grilling` — stress-test the design when the bug exposes a design gap;
  recommend the documented route when needed
- `/auth-overview` — Azure AD / TokenX / ID-porten / Maskinporten / Texas (the mechanisms behind auth diagnosis)
- `/architecture-review` — review NAV consequences of architectural changes that would have prevented the bug
