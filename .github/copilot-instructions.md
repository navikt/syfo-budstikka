# Copilot instructions — syfo-budstikka

Ktor backend (Kotlin, Nav). Java 25, Gradle, Netty. Gradle group `no.nav.syfo`;
source packages under `no.nav.budstikka`.

This repository targets GitHub Copilot CLI. The checked-in repository files are
the operative contract; no agent needs runtime access to another repository.
Load detail only when the task needs it, and answer users in their own
language.

## Repository discovery

Read detail only when its condition applies. These ordinary Markdown links are
context pointers, not eager includes:

- When creating, updating, or publishing tracker work, read
  [`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md).
- When domain terminology, domain documentation, or a durable decision is in
  scope, read [`docs/agents/domain.md`](../docs/agents/domain.md).
- Before creating or substantially rewriting an artifact, read
  [`docs/agents/language-policy.md`](../docs/agents/language-policy.md).
- When creating, revising, reviewing, or diagnosing a skill, read
  [`docs/agents/skill-invocation.md`](../docs/agents/skill-invocation.md).
- When copying, adapting, or updating upstream material, read
  [`docs/agents/provenance.md`](../docs/agents/provenance.md).
- When maintaining the Grillmester setup or its rationale, read
  [`GRILLMESTER.md`](GRILLMESTER.md).

## Delivery boundary

This boundary overrides any workflow or skill instruction that says to commit
or deliver automatically.

Do not push, open or modify an issue or pull request, merge, or perform another
shared GitHub action unless the user explicitly requested it. Local commits
also require an explicit user request.

## Agent setup

- **@grillmester** owns the non-trivial workflow from clarification through
  delivery synthesis and delegates one bounded implementation slice at a time.
- **Kokk** is the internal writer for that slice and returns deterministic
  evidence with a structured status. Kokk never stages or commits.
- **Grill-inspektor** is the internal independent read-only reviewer.

### Repository risk and review policy

- R0–R2 review is opt-in. Treat auth and authorization, PII, core domain rules
  or state machines, schema and data migration, external API or event contracts,
  NAIS access and deployment, and broad architecture as R3/R4 red signals.
- For R3/R4, the latest Inspector review of the current diff must be `APPROVED`.
  `CONCERNS` is acceptable only when a human explicitly accepts the named
  concerns. Any subsequent diff change invalidates the verdict and requires
  fresh deterministic evidence and a fresh review.
- A human may waive Inspector explicitly. Before work is described as
  merge-ready, an accepted concern or waiver must be documented in the issue or
  pull request, not left only in ephemeral conversation.
- `/nav-architecture-review` reviews relevant NAV platform, security, privacy,
  operability, and team-boundary choices. It does not write ADRs.
  `/domain-modeling` owns the ADR gate and durable domain writes after the user
  chooses the documented route.

### Task state and evidence

The conversation and active issue or pull request are the default task record.
Skills return plans, findings, and command evidence to their caller. A small
task-local `.grill/` file is optional only when the caller explicitly selects
it to carry state across a real session seam; a skill must not require or create
`.grill/` artifacts on its own. Task and pull request acceptance criteria remain
the requirements source.

## Standing principles

These apply to all code assistance in this repository.

- **Naming:** Norwegian words only for domain terms (`Brukervarsel`,
  `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, `Brev`). Everything
  else — mechanics, verbs, plumbing, technical identifiers — is English
  (`lagre`→`save`, `innhent`→`fetch`, `erDod`→`isDead`). Full rule with
  examples in `.github/instructions/kotlin.instructions.md`.
- **Quality gates are deterministic:** `./gradlew test`,
  lint, and build decide pass or fail. Never claim something "looks right"
  without fresh evidence — command, output, and exit code in the same message.
