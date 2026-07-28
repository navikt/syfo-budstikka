# Copilot instructions — syfo-budstikka

Nav Kotlin/Ktor backend: Java 25, Gradle, Netty, group `no.nav.syfo`, packages
under `no.nav.budstikka`. Follow `docs/agents/language-policy.md`; answer users
in their language.

## Copilot CLI roles

- **@barista** (Terra) is the user-selected, solo-first front door:
  `copilot --agent barista --model gpt-5.6-terra --context default`.
- **@grillmester** (Opus) clarifies and delegates; it never implements.
- Internal **kokk** (Terra) implements one validated vertical slice.
- Internal, read-only **grill-inspektor** (Opus) reviews the task contract,
  exact diff, and fresh evidence.

Repository defaults use Terra/default context. Start custom roles with their
documented model, confirm the effective outer model, and fail closed when an
internal task's explicit model is unavailable.
Inspector is mandatory per R3/R4 Grillmester/Kokk slice. Wholly R0–R2 work gets
one complete-diff review only when material, concerned, or requested, and after
opt-in. Aggregate R3/R4 requires integrated review; never duplicate one slice.
Barista follows the same opt-in rule and routes R3/R4 before implementation.

## Brief, evidence, and authority

`IMPLEMENTATION_BRIEF v1` is the task-scoped Grillmester–Kokk contract; see
`docs/agents/implementation-brief.md`.

The checked-in repository files are the operative contract;
`.github/GRILLMESTER.md` records the human overview and provenance. When
decisions remain, inspect facts and ask
one user-owned question at a time. `/grill-with-docs` and `/wayfinder` are
manual; missing Wayfinder label mappings stop writes and never authorize new
labels.

Load guidance progressively. Briefs and evidence belong to one slice, never
global mutable task state.

## Invariants

- **Language:** `README.md` is Norwegian. Canonical domain terms may be
  Norwegian in code; all technical and mechanical identifiers are English.
  Agent-facing material is English. See `docs/agents/language-policy.md`.
- **Architecture:** `domain` has no outward dependencies; `application`
  depends only on `domain` and application ports; infrastructure implements
  ports; bootstrap wires the graph.
- **Kotlin floor:** use structured concurrency—never `GlobalScope` or blocking
  work on an event loop. Use version catalogs, keep Flyway append-only, and
  make Kafka consumption idempotent.
- **Fresh evidence:** use commands, relevant output, and exit codes for the
  current brief, not model assertions.
- **Scoped writes:** Kokk starts from a validated clean boundary and stays in
  scope. With `atomic-local`, it may commit only its own files; never push, open
  a pull request, merge, amend, rebase, or reset.
- **CLI only:** this setup targets Copilot CLI, not IDE- or cloud-agent
  behavior.
