# ADR 0010 — Container build with Jib instead of Dockerfile

- Status: Decided and in use
- Date: 2026-07-22
- Related: Docker/GitHub Actions instructions, deploy workflows, `build.gradle.kts`

## Context

The service builds a container image from Gradle without a Dockerfile. Previous
Ktor `docker { }` tasks use `Task.project` at runtime, which Gradle 10 rejects.
Chainguard remains the only allowed base image, with verifiable post-push signing.

## Decision

Use pure Jib through `JibExtension`, not Ktor `docker { }` tasks or
`nais/docker-build-push`:

1. Gradle `jib.from` uses Chainguard JRE 25; `jib.to` reads repository/tag from
   Gradle properties/environment; `jib.container` owns runtime settings. Jib
   cannot yet read Chainguard OCI Image Index v1.1 directly, so
   `pullChainguardBaseImage` pre-pulls to Docker and `from.image` uses `docker://`.
   `jib`, `jibBuildTar`, and `jibDockerBuild` depend on it.
2. Commit-pinned `nais/login` (v0) authenticates GAR and supplies registry
   output. Deploy runs Gradle build/Jib with repository/tag, reads
   `build/jib-image.digest`, and sends `repo@digest` to commit-pinned
   `nais/attest-sign` (v2.0.14) for SLSA attestation and cosign signing. Image
   path is derived, not hard-coded; tag is
   `YYYY.MM.DD-HH.mm-<short-sha>`.
3. PR/merge CI runs compilation, lint, and tests without OIDC or registry
   credentials. After merge, the trusted `main` deployment builds and pushes
   the image once; `deploy-nais.yaml` deploys the same `repo:tag` to dev and
   then production.

There is no Dockerfile rollback/parallel path; reintroducing one needs a new ADR
because it duplicates image contract and changes supply chain.

## Consequences

Base image, Java version, JVM flags, and runtime metadata live in Gradle. Docker
remains required only for the bounded OCI 1.1 pre-pull workaround. Explicit
attestation/signing retains supply-chain guarantees. Jib- or Chainguard-specific
failures may first surface in the trusted `main` deployment; this is accepted
to keep pull-request workflows free of registry credentials and OIDC.
The reviewed `nais/deploy` action still inherits a mutable transitive container
tag; the trusted-policy runbook records that external supply-chain limitation
and the repository-wide Nais OIDC trust limitation.
