# Agent guide

## Agent skills

### Issue tracker

Work is tracked in GitHub Issues for `navikt/syfo-budstikka`; reuse an existing
issue and pull request when they exist. See
[`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).

### Triage labels

Use only labels that already exist in the repository; a skill must not create
new labels. See [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).

### Domain docs

This repository has one domain, with a short index in `docs/context.md`,
canonical vocabulary in `docs/glossary.md`, and binding decisions in
`docs/adr/`. Active `Bnn` references are defined in the non-ambient
`docs/decisions.md` register. See
[`docs/agents/domain.md`](docs/agents/domain.md).

### Implementation brief

A task is handed off through an explicit task-scoped brief in the conversation,
an issue or pull request, or a named task-scoped artifact path. See
[`docs/agents/implementation-brief.md`](docs/agents/implementation-brief.md).

### Language

Repository artifacts follow [`docs/agents/language-policy.md`](docs/agents/language-policy.md):
agent-facing material and technical names are English, while the README is
Norwegian and canonical Norwegian domain terms remain Norwegian in code.

Hovmester is the upstream source for the team's reusable agent contracts. The
checked-in files in this repository are the only operative contract here;
agents do not need access to Hovmester. Provenance and local choices are
documented in [`.github/GRILLMESTER.md`](.github/GRILLMESTER.md).
`navikt/copilot` is a secondary upstream and does not override the local
contract.

## Delivery boundary

Do not push, open or modify an issue or pull request, merge, or perform another
shared GitHub action unless the user explicitly requested it. Local commits
also require an explicit request or a complete brief that permits
`atomic-local`.
