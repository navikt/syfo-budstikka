---
description: "Always-on floor for GitHub Actions workflows: pinning, permissions, deploy invariants and the boundaries that are not discoverable from the existing workflows."
applyTo: ".github/workflows/**"
---

# Workflow floor

The existing workflows are the pattern reference — read them before writing one. This file only holds what is not discoverable or enforced there.

- Pin third-party actions to a full commit SHA with a version comment.
  Exception: `nais/*` actions use stable semver tags (zizmor flags this;
  the finding is accepted).
- Explicit least-privilege `permissions` per workflow/job, `timeout-minutes`
  on every job, `concurrency` on deploys. Never `permissions: write-all`.
- Images are built with Jib via `nais/login` — not `nais/docker-build-push`.
  Every environment deploys the image from the single build job.
- Never `pull_request_target` with checkout of the PR branch, and never log
  secrets in workflow output.
- Lint with `actionlint` and `zizmor` before pushing.

Ask first: new secrets or environment variables, changes to deploy order
(dev → prod), new reusable workflows, or changes to the `team` value.
