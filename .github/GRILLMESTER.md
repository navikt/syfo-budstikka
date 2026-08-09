# Grillmester — GitHub Copilot CLI setup

This document is the human-facing map of the repository's agent setup. The
checked-in agent profiles, skills, and instructions are the operative
contracts; this page does not duplicate their full workflows.

## Components

- **Grillmester** (`.github/agents/grillmester.agent.md`) owns clarification,
  design, risk, routing, checkpoints, verification, and delivery synthesis.
- **Kokk** (`.github/agents/kokk.agent.md`) implements one bounded vertical
  slice from a concise task brief and returns evidence.
- **Grill-inspektor** (`.github/agents/grill-inspektor.agent.md`) performs an
  independent read-only review when selected or required by risk.
- **Barista** (`.github/agents/barista.agent.md`) owns ordinary repository work
  solo-first, from request through verified result. It recommends Grillmester
  when a durable user-owned choice or red signal appears, and never invokes
  Grillmester or Kokk itself.
- **Researcher** (`.github/agents/researcher.agent.md`) resolves one claimed
  `/wayfinder` research ticket read-only and returns a compact sourced note.
- **Skills** (`.github/skills/`) provide progressively disclosed workflows and
  domain knowledge. Their descriptions are the discovery surface.
- **Instructions** (`.github/instructions/` and
  `.github/copilot-instructions.md`) hold repository and path-specific policy.

## Workflow shape

Grillmester uses a seven-phase loop: grill, design, plan, implement, verify,
deliver, and verify in the environment. R0/R1 work with locked requirements and
no durable decision or red signal uses a fast path to one Kokk slice plus
deterministic verification. More complex work retains the full loop.

Implementation delegation uses a concise human-readable task brief, not a
baseline digest, manifest, generated patch artifact, or global state protocol.
Inspector receives the task criteria, task-scoped diff, deterministic evidence,
and only named relevant decisions. When Kokk implemented the change, its brief
and result are also supplied as provenance. The current-diff verdict and waiver
rules live once in `.github/copilot-instructions.md`.

## Context and documentation

- Task and pull request acceptance criteria own requirements.
- `docs/agents/domain.md` owns local documentation paths, form, narrow loading,
  legacy decision handling, and link policy. `/domain-modeling` applies the
  portable ADR gate and durable-write workflow under that policy.
- `.grill/` is optional task-local scratch space. It is not a required
  cross-task state system.
- `/handoff` is for a real session seam, not agent-to-agent delegation.

## Runtime verification

Runtime claims come from current agent frontmatter, Copilot discovery, the
repository gates, and an authenticated bounded pilot. Historical setup prose
does not prove which configuration the runtime applied.

Grill-inspektor's tool grants use the reviewer runtime's vocabulary (`view`,
`grep`, `glob`); every other agent uses `read`/`search`. The divergence is
deliberate and pinned per agent in `scripts/validate-agent-models.sh`.

Sources, reviewed revisions, and local adaptations are recorded in
`docs/agents/provenance.md`. The repository files remain the only runtime
dependency.
