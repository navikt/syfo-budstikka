# Agent guide

This repository targets GitHub Copilot CLI. The checked-in repository files are
the operative contract. Load detail only when the task needs it.

## Repository discovery

- Agents `.github/agents/` · Skills `.github/skills/` · Path-scoped
  instructions `.github/instructions/`
- Work tracking: [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md)
- Tracker labels: [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md)
- Domain documentation: [`docs/agents/domain.md`](docs/agents/domain.md)
- Artifact language: [`docs/agents/language-policy.md`](docs/agents/language-policy.md)
- Skill invocation: [`docs/agents/skill-invocation.md`](docs/agents/skill-invocation.md)
- Upstream sources and revisions: [`docs/agents/provenance.md`](docs/agents/provenance.md)

## Source precedence

The checked-in files in this repository are the sole operative contract; no
agent needs runtime access to another repository. `navikt/hovmester` is the
team's upstream source for reusable agent contracts. `mattpocock/skills` and
`navikt/copilot` are reviewed inputs and never override a local contract. Port
concrete upstream changes deliberately after reviewing the diff.

## Delivery boundary

This boundary overrides any workflow or skill instruction that says to commit
or deliver automatically.

Do not push, open or modify an issue or pull request, merge, or perform another
shared GitHub action unless the user explicitly requested it. Local commits
also require an explicit user request.
