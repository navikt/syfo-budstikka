# Copilot instructions — syfo-budstikka

Ktor backend (Kotlin, Nav). Java 25, Gradle, Netty. Gradle group `no.nav.syfo`;
source packages under `no.nav.budstikka`.

`AGENTS.md` holds repository discovery, source precedence, and the delivery
boundary. This file holds only the Copilot-specific agent setup. Follow
`docs/agents/language-policy.md` for artifact language, and answer users in
their own language.

## Agent setup

- **@grillmester** (Opus 4.8) is the orchestrator and inline implementer for
  non-trivial work. It runs a phase loop — grill, design, plan, implement,
  verify, deliver — and writes working memory to `.grill/`.
- **grill-inspektor** (GPT-5.5, internal) is a fresh cross-model reviewer:
  **opt-in**, recommended-on for high-risk work. This is where "both model
  families look at the work" is preserved while staying cost-controlled.
- There is no weak model tier. Quality comes from a strong model plus
  deterministic gates, not from cheap intermediaries.

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
- **Inline writing:** work that requires judgement happens in the main thread.
  Subagents are used only for read-only exploration, cross-model verification,
  and opt-in divergent design exploration (design-it-twice).
- **Skill invocation:** a skill `description` supplies discovery and
  automatic-selection signals; frontmatter constrains which invocation routes
  are available. Manual-only skills require `/skill-name`.
- **Disk as memory:** longer work tracks decisions, plan, and verification in
  `.grill/`. `STATE.md` is read first and kept small and curated. Checkpoint at
  phase boundaries and proactively before context gets tight; do not guess at a
  context-window percentage you cannot measure.

## Model policy

Roles are pinned in the agent files and validated deterministically by
`scripts/validate-agent-models.sh`, which fails hard and writes
`.grill/MODELL-STATUS.md`. A model never asserts which model it is.

Design overview and rationale: `.github/GRILLMESTER.md`.
