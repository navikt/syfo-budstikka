# Copilot instructions — syfo-budstikka

Ktor backend (Kotlin, Nav). Java 25, Gradle, Netty. Gradle group `no.nav.syfo`;
source packages under `no.nav.budstikka`.

This repository targets GitHub Copilot CLI. The checked-in repository files are
the operative contract; no agent needs runtime access to another repository.
Load detail only when the task needs it, and answer users in their own
language.

## Repository discovery

Loaded on demand:

- Work tracking and tracker labels:
  [`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)
- Domain documentation: [`docs/agents/domain.md`](../docs/agents/domain.md)
- Artifact language:
  [`docs/agents/language-policy.md`](../docs/agents/language-policy.md)
- Skill invocation:
  [`docs/agents/skill-invocation.md`](../docs/agents/skill-invocation.md)
- Upstream sources and revisions:
  [`docs/agents/provenance.md`](../docs/agents/provenance.md)
- Design overview and rationale: [`GRILLMESTER.md`](GRILLMESTER.md)

## Source precedence

`navikt/hovmester` is the team's upstream source for reusable agent contracts.
`mattpocock/skills` and `navikt/copilot` are reviewed inputs and never override
a local contract. Port concrete upstream changes deliberately after reviewing
the diff, and move a recorded revision only in the same change that adopts it.

## Delivery boundary

This boundary overrides any workflow or skill instruction that says to commit
or deliver automatically.

Do not push, open or modify an issue or pull request, merge, or perform another
shared GitHub action unless the user explicitly requested it. Local commits
also require an explicit user request.

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
- **Skill invocation:** call `/skill-name` explicitly when a task enters a
  domain that has a skill. Descriptions surface automatically, but only an
  explicit call loads the skill body.
- **Disk as memory:** longer work tracks decisions, plan, and verification in
  `.grill/`. `STATE.md` is read first and kept small and curated. Checkpoint at
  phase boundaries and proactively before context gets tight; do not guess at a
  context-window percentage you cannot measure.

## Model policy

Roles are pinned in the agent files and validated deterministically by
`scripts/validate-agent-models.sh`, which fails hard and writes
`.grill/MODELL-STATUS.md`. A model never asserts which model it is.
