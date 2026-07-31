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

- **@grillmester** requests `claude-opus-4.8` in frontmatter and is the
  orchestrator and inline implementer for non-trivial work. It runs a phase
  loop — grill, design, plan, implement, verify, deliver — and writes working
  memory to `.grill/`.
- **grill-inspektor** requests `gpt-5.5` and is an opt-in, fresh, read-only
  reviewer recommended for high-risk work. It adds a cross-family perspective
  only when separate runtime evidence confirms that both requested models were
  actually selected; its fresh context and read-only boundary do not depend on
  that claim.
- No weak model tier is declared. Quality relies on the requested strong-model
  policy plus deterministic gates, not on an asserted runtime identity.

## Standing principles

These apply to all code assistance in this repository.

- **Naming:** Norwegian words only for domain terms (`Brukervarsel`,
  `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, `Brev`). Everything
  else — mechanics, verbs, plumbing, technical identifiers — is English
  (`lagre`→`save`, `innhent`→`fetch`, `erDod`→`isDead`). Full rule with
  examples in `.github/instructions/kotlin.instructions.md`.
- **Quality gates are deterministic and outside the model:** `./gradlew test`,
  lint, and build decide pass or fail. Never claim something "looks right"
  without fresh evidence — command, output, and exit code in the same message.

## Model policy

Roles declare model pins in the agent files. `scripts/validate-agent-models.sh`
fails when a declaration is missing or outside the repository allowlist and
writes `.grill/MODELL-STATUS.md`; it does not attest runtime availability,
selection, or fallback. A model never asserts which model it is.
