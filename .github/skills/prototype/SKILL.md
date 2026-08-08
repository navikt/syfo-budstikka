---
name: prototype
description: "Use when a design is too uncertain for paper and you want to test it with runnable throwaway code BEFORE you commit to it — data model, state machine, API/error contract or Kafka flow (idempotency/replay). Triggers: 'spike this', 'let me play with it', 'is the data model right', 'try a couple of variants'."
---

# prototype

A spike is **throwaway code that answers one question**. It lives in its own
track, is clearly marked as throwaway, and dies once the answer is found. The
lesson — not the code — is returned to the conversation or the active task.

This is a backend repository (Ktor / no.nav.syfo). A spike is never about looks, always about **behavior and shape**: how a state evolves, what data a model can actually represent, what an API response looks like, or what a consumer does when the same event arrives twice.

## When this is the right tool

- In the middle of a `/grilling` session a choice comes up that neither of you
  can settle on paper — "does this state machine feel right when X happens just
  before Y?".
- You want to push a data model through the ugly edges before writing the Flyway migration.
- You want to see the actual JSON and error contract an endpoint is going to return, before committing to it.
- You are unsure whether a Kafka consumer is idempotent / replay-safe, and want to feed it a sequence of records by hand.

If the question is already settled and you are just going to build — wrong tool. Use `/tdd` and write real code test-first.

## Choose the angle

Write the question down in one sentence at the top of the spike file or in the
active task before you code. A spike that answers the wrong question is pure
waste.

- **"Does the model/state machine feel right?"** → build a tiny interactive `main()` that lets you drive the model by hand and watch the state change after each action.
- **"What should the API shape / error contract be?"** → sketch request/response as `data class`es, start a minimal `embeddedServer` with a single route, and `curl` against it until the shape feels right.
- **"Does the Kafka flow hold up?"** → isolate the consumer/producer logic as a pure function over a list of `record`s, feed it events in a chosen order (duplicate, replay, out-of-order) and see what falls out.

## Rules (apply to all angles)

1. **Throwaway from day one, and clearly marked.** Put the spike close to what it explores (the same package under `no.nav.syfo`), but in its own track that nobody confuses with production — e.g. `src/test/kotlin/no/nav/syfo/spike/` or a `SpikeXxx` file. Never under `main` where it could end up in a deploy.
2. **Isolate the logic behind a clean interface.** Whatever actually answers the question — the reducer `(state, event) -> state`, the state machine, the set of pure functions, or the `data class`es — must be liftable straight into real code later. No I/O, no `println` for control flow, no DB inside the logic. The runner shell around it is garbage; the core is the only part worth keeping.
3. **One command to run.** Use Gradle the way the repository already does — a throwaway `fun main()` run via a `JavaExec` task or from the IDE, or a `@Test` that drives the model. The user must never have to remember a path.
4. **No persistence by default.** State lives in memory. Persistence is what the spike *checks*, never something it rests on. If you must hit a database, use an in-memory `H2`/Testcontainers instance or a local schema named something that shouts `SPIKE — delete me`. Never a shared or real database.
5. **No auth, no polish.** Drop TokenX/Azure AD validation, `StatusPages`, retry, metrics, logging beyond what makes the spike *runnable*. Those are precisely the things whose shape you are testing, never something the spike implements.
6. **Show the state.** After each action (model/machine), or for each fed record (Kafka), or in each response (API): print the entire relevant state — one line per field or formatted JSON — so you see exactly what changed.
7. **Delete or absorb when you are done.** Once the question is answered: either delete the spike, or lift the validated core into real code (test-first via `/tdd`). Do not let it rot in the repository.

## Connect to the phase loop (@grillmester)

The spike is a side tool *inside* the design and planning phase, not a track of its own:

- **Triggered from phases 1–2.** When `/grilling` hits a blind spot nobody can
  settle (idempotency, an ugly state transition, a doubtful model), you pause
  the grilling, spike the answer, and go back.
- **The answer is the only thing that is stored.** The task scope goes to the
  issue/plan and maintained detail to the relevant topic document. When a new
  concept or a qualifying decision ought to be written down for good, recommend
  the documented route and wait for the user's choice before `/domain-modeling`
  updates the glossary or an ADR. Use `/architecture-review` only when NAV
  consequences are relevant.
- **Note open answers.** If you are running AFK and the user has not confirmed
  the verdict yet: return the question + the preliminary finding to the active
  task. Write it in a task-local `.grill/` only when the calling workflow has
  chosen that.

## Anti-patterns

- **Do not write tests for the spike.** If the spike needs tests it is no longer a spike — then it must be lifted into real code and tested there.
- **Do not connect to a real database, Kafka cluster or TokenX.** Feed events and state from memory. The question is "does the shape hold?", not "does the infrastructure work?".
- **Do not generalize.** No "what if we later want to support X". The spike answers one question.
- **Do not mix the core and the shell.** If the reducer/machine references `println`, HTTP or a `Connection`, it is no longer liftable. Keep the runner as a thin shell over a pure core.
- **Do not promote spike code straight to production.** It was written under spike conditions (no auth, no error handling). Write it properly again when you fold it in.
