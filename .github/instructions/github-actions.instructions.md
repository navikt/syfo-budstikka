---
description: "Applies when changing GitHub Actions for this Kotlin/Ktor backend: Gradle/Jib builds, Nais authentication, and dev/prod deployment."
applyTo: ".github/workflows/**"
---

# GitHub Actions — syfo-budstikka

This repository builds with Gradle and Jib and deploys to Nais. Read the
existing workflow completely before changing it; do not replace the current
image or deployment flow from memory.

## Current workflow boundaries

- `ci.yaml` calls `ci-reusable.yml` for pull requests and merge queue.
- CI validates repository agent model pins, runs `./gradlew build --no-daemon`,
  and builds the container with Jib without pushing it.
- `deploy.yaml` builds and pushes one Jib image on `main`, signs its immutable
  digest, deploys that image to dev, then reuses the same image for prod.
- `deploy-nais.yaml` is the reusable application deployment.
- `deploy-topic.yaml` owns Kafka Topic deployment. It currently deploys dev
  from pull-request runs and prod only from `main`; changing that behavior is a
  security and operational decision, not a cleanup.

Keep Java 25 aligned with `jvmToolchain` in `build.gradle.kts`.

## Security and determinism

- Pin third-party actions to a full commit SHA with a version comment.
  Existing `nais/*` actions use the team's accepted tag policy; verify current
  Nav guidance before changing that exception.
- Set explicit least-privilege permissions. Registry login and Nais deployment
  require OIDC; ordinary repository inspection does not.
- Never use `permissions: write-all`.
- Keep secrets and OIDC out of untrusted pull-request code paths.
- Never use `pull_request_target` to execute or source candidate-controlled
  workflows, scripts, hooks, or application code.
- Preserve `timeout-minutes` and deployment concurrency.
- Never print credentials, tokens, registry authentication, or secret values.

## Build and deploy invariants

- Use Jib; this repository deliberately has no parallel Dockerfile build.
- Build and test before pushing an image.
- Sign the immutable `repository@digest` produced by Jib.
- Deploy the same built image to dev and prod.
- Keep runtime secrets in Nais configuration, not workflow files or Gradle.
- Keep the required `Merge gate` fail-closed when CI fails, is cancelled, or
  is skipped.

## Ask before

- adding secrets or broader permissions
- changing the `team`, registry, image signing, or deployment order
- enabling a new pull-request deployment path
- changing required checks, merge queue, or repository rulesets

GitHub review policy is enforced by CODEOWNERS and repository settings. It is
separate from agent behavior and must not be reimplemented through custom
candidate-checkout or digest-validation workflows.
