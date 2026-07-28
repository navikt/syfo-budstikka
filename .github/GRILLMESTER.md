# Grillmester — agent setup for syfo-budstikka

This is the human-readable operating contract. Executable configuration lives
in `.github/agents/`, `.github/instructions/`, and `.github/skills/`.
Hovmester is the upstream source for the team's reusable workflow. The
checked-in files in this repository are the only operative contract here;
agents do not need access to Hovmester. This document records provenance and
narrow local choices for maintainers.

## Operating topology

```text
User → Barista (Terra) ──ordinary, narrow work──→ delivery on explicit request
          ├── optional, approved review ─────────→ Grill-inspektor (Opus)
          └──→ Grillmester (Opus) → Kokk (Terra) → Grill-inspektor (Opus)
                 clarify / decide       one slice       by risk or opt-in
```

- **Barista** is the recommended, explicitly selected front door for ordinary
  narrow work
  (`copilot --agent barista --model gpt-5.6-terra --context default`). Confirm
  the effective model after startup. A plain CLI session is generic, even
  though repository settings use Terra/default context.
- **Grillmester** owns clarification, design, risk assessment, a complete
  brief, and delegation. Start it with
  `copilot --agent grillmester --model claude-opus-5 --context default` and
  confirm the effective model. It does not implement or deliver through Git.
- **Kokk** implements exactly one testable vertical slice from a complete
  `IMPLEMENTATION_BRIEF v1`, returning fresh command evidence and a bounded
  result.
- **Grill-inspektor** is the independent read-only implementation reviewer. It
  compares the governing task contract, actual diff, and fresh verification.

There are exactly four repository roles; personal or organization-level agents
may remain discoverable but are outside this workflow.

## Normal choices

Grilling is natural and default whenever requirements, trade-offs, or scope are
not locked: inspect repository facts first, then ask one user-owned question at
a time with a recommendation and consequence. Use the manual
`/grill-with-docs` option when confirmed discussion should also create durable
glossary or ADR documentation.

`/wayfinder` is a manual, explicit opt-in—not an automatic escalation. Recommend
it only for a decision route with dependent work that genuinely spans multiple
sessions. Before any tracker write, its required label mappings must already be
present in `docs/agents/triage-labels.md`. Missing mappings are a safe
prerequisite: return `NEEDS_DECISION` and do not create labels, substitute
labels, or create a partial map.

## Brief, review, and delivery

The task-scoped `IMPLEMENTATION_BRIEF v1` is the Grillmester–Kokk handoff.
`docs/agents/implementation-brief.md` defines its fields, preflight, statuses,
and canonical R0–R4 rubric. Before delegation, Grillmester and Kokk both check
the complete brief and a clean Git boundary. Preserve existing work by waiting
or using a separate clean worktree, never by discarding it. The brief, current
diff, and verification are the evidence record; do not create a shared mutable
task log.

Within the Grillmester/Kokk route, Grill-inspektor is mandatory for every R3/R4
slice. Work that remains wholly R0–R2 gets an optional final review only when it
is material, a result has concerns, or the user asks, and only after the user
opts in. When delivery combines multiple slices, reassess the aggregate diff:
aggregate R3/R4 requires one final integrated review binding every brief/result
pair from the earliest baseline; an optional R0–R2 review uses the same complete
integrated boundary. Per-slice reviews do not cover cross-slice interactions,
and one slice never needs a duplicate final call. A direct Barista task has no
automatic escalation: after material upper-R2 work without red flags, Barista
may offer one optional Inspector review, but only proceeds after the user
accepts. Any R3/R4 characteristic stops direct Barista implementation and routes
to Grillmester first.

Any review approves evidence, not delivery. Pushes, pull requests, merges,
issue changes, and local commits require the authority stated in the task,
brief, or an explicit user request. Kokk may make one `atomic-local` commit
only when its brief permits it.

Inspector is an advisory quality boundary, not an adversarial attestation or a
security approval. The caller supplies the complete baseline-to-worktree diff,
task contract, and fresh verification, then checks that the boundary is still
unchanged after review. Explicit per-task model selection, deterministic CI,
CODEOWNERS, and human review remain separate controls; Inspector never replaces
them.

## Context and quality

- `docs/context.md` is a short orientation index; `docs/glossary.md` defines
  canonical terms; ADRs bind hard-to-reverse decisions. A named `Bnn` is looked
  up in `docs/decisions.md`, which is deliberately non-ambient.
- Load only the context needed for the current question or slice. Keep runtime
  prompts portable and concise; do not encode historical rollout narratives in
  them.
- Repository settings set only supported repository defaults and disable known
  colliding personal skills. Memory and personal custom-agent availability are
  user-level Copilot CLI controls and cannot be disabled by this repository.
  The workflow therefore never depends on memory or on an undeclared personal
  agent.
- Use Terra for ordinary dialogue and implementation, and Opus for coherent
  design and independent inspection. Start with the narrowest relevant check;
  deterministic verification remains the normal quality barrier.
- Give Inspector one coherent, complete diff rather than a partial view. Split
  a change or pull request when its diff and governing context cannot be
  reviewed coherently in one pass; never hide unrelated work or omit a slice to
  imply complete coverage.

## Repository boundaries

GitHub Issues and pull requests are the shared work record. Reuse existing
issues and labels, and never create labels unless the user explicitly asks for
that shared state change. Repository artifacts follow
`docs/agents/language-policy.md`: agent-facing and technical material is
English, while README/product language and canonical Norwegian domain terms
remain Norwegian where appropriate.

Port concrete upstream changes deliberately through Hovmester. Keep reusable
contracts portable enough to contribute back; repository-specific details
belong in the small files under `docs/agents/`.

## Upstream provenance

The pilot was compared with fixed upstream revisions. These pointers are
maintainer provenance, not runtime dependencies or a second source of truth:

| Source | Reviewed revision | Reviewed |
|---|---|---|
| [`navikt/hovmester`](https://github.com/navikt/hovmester) | `78d38108f7b6ca44b6eb0056801c1a79d3c60912` | 2026-07-28 |
| [`mattpocock/skills`](https://github.com/mattpocock/skills) | `ed37663bd925f85d9a2eed453d0a8929b8d67f67` (`v1.1.0`) | 2026-07-21 |
| [`navikt/copilot`](https://github.com/navikt/copilot) | `fd01c00fb41bbdb6c562f6c48028319547ac250b` | 2026-07-26 |

Review concrete upstream diffs before porting them. Hovmester remains the team
source; Matt Pocock's skills and `navikt/copilot` are inputs.

## Runtime qualification

The pilot was qualified against GitHub Copilot CLI `1.0.75` on 2026-07-28.
Repository-versus-user settings, manual-skill discovery and callback behavior,
task-call `model`/`context_tier`, and model fallback or multiplier handling are
runtime behavior, not portable repository guarantees. After a CLI upgrade,
rerun the model gate and the explicitly approved paid live smoke before relying
on those behaviors.
