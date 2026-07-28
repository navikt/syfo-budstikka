---
description: "Applies when changing the container image in build.gradle.kts for this Ktor backend. The service builds and pushes with Jib and deliberately has no Dockerfile."
applyTo: "build.gradle.kts"
---

# Container image — Jib and Chainguard

This repository has no `Dockerfile` or `.dockerignore`. Configure the container
image exclusively in `build.gradle.kts` through `JibExtension`; also see
[ADR 0010](../../docs/adr/0010-container-bygg-med-jib.md).

## Fixed contract

- Use the Chainguard JRE from Nav's registry with the same major version as
  `jvmToolchain`:
  `europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25`.
- Preserve `from.image = "docker://…"` and the dependency from `jib`,
  `jibBuildTar`, and `jibDockerBuild` to `pullChainguardBaseImage`. Jib cannot
  currently read Chainguard's OCI Image Index v1.1 directly; `docker pull` is
  the bounded workaround.
- Set the image destination through `docker.image.repository` and
  `docker.image.tag`, or their corresponding environment variables. Never
  hard-code a GAR repository or deployment tag in Gradle.
- Keep runtime configuration in `jib.container`: `mainClass`, port `8080`, JVM
  flags, and `TZ`. Nais supplies secrets at runtime; never put them in image
  configuration.

## CI and local verification

The pull-request gate runs compilation, lint, and tests without registry
credentials. Deployment runs `nais/login` before `./gradlew … build jib`, reads
`build/jib-image.digest`, and signs the digest with `nais/attest-sign`. Change
these parts together when the image contract changes.

Locally, Jib tasks require a running Docker daemon for the pre-pull step. That
is not a reason to add or reintroduce a `Dockerfile`.

## Boundaries

### Always

- Match the Java major version across the toolchain, Chainguard base, and CI.
- Verify image configuration through the trusted `main` deployment after
  merge.
- Preserve attestation after the Jib push in the deployment workflow.

### Ask first

- A new base image, changed JVM flags, or a new image-tagging scheme.
- Removing or replacing the OCI 1.1 workaround.

### Never

- Reintroduce a parallel Dockerfile or fat-JAR flow.
- Put secrets in Gradle or image configuration.
- Push an image from the pull-request gate.
