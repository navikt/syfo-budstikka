---
description: "Used when writing or changing a Dockerfile or .dockerignore in a Nav repository — base image selection, multi-stage builds, non-root and security. Portable core; repository facts live in the adapter section at the end."
applyTo: "**/Dockerfile*, **/.dockerignore"
---

# Docker — Nav

Standards for Dockerfiles in Nav repositories: Chainguard base images, multi-stage builds, non-root and security practice. The core is stack-agnostic; anything specific to this repository lives in the [repository adapter](#repository-adapter) at the end.

Reference: [Chainguard base images — sikkerhet.nav.no](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages)

## Base images — Chainguard

Nav pays for [Chainguard base images](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages) with minimal vulnerabilities. Use these instead of Google Distroless or full OS images.

### Nav's private registry (JVM, Node, Python)

```
europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/<image>:<tag>
```

Available images include `jre`, `jdk`, `node`, `python`.

### Free Chainguard images (Go, nginx, static)

```
cgr.dev/chainguard/<image>:<tag>
```

### Tags

- Use the major version that matches the toolchain the repository builds with. Chainguard does not backport.
- Do not pin SHAs manually. Set up a workflow for regular rebuilds.
- Use [digestabot](https://github.com/navikt/digestabot) if you want SHA pinning with automatic PRs.

```dockerfile
# ✅ Chainguard from Nav's registry — match major against the build toolchain
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/node:22-slim

# ⚠️ Google Distroless works, but Chainguard is preferred in Nav
FROM gcr.io/distroless/java21-debian12:nonroot

# ❌ Avoid full OS images
FROM ubuntu:22.04
FROM openjdk:25
```

## Build the artifact in CI, copy it in (recommended)

Build the deployable artifact (jar, binary, bundle) in CI and copy it into the image — then a simple single-stage Dockerfile is enough, and the build toolchain stays out of the runtime image.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*-all.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

`EXPOSE` is not required for NAIS, but can document the port.

## Multi-stage (building inside the Dockerfile)

Use only if you do not build the artifact in CI. Then it must be multi-stage so the build toolchain stays out of the runtime image.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25 AS build
WORKDIR /app
# Dependencies first → better layer caching (see below)
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew installDist --no-daemon

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
ENV TZ="Europe/Oslo"
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

## Layer caching

```dockerfile
# ✅ Copy build scripts and dependency manifests first → cache holds while dependencies are unchanged
COPY build.gradle.kts settings.gradle.kts gradle.properties gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon
COPY src ./src

# ❌ Invalidates the cache on any source change
COPY . .
```

The same principle applies to every stack: copy the dependency manifest (`package.json`, `go.mod`, `requirements.txt`) and resolve dependencies before copying sources.

## Security

```dockerfile
# ✅ Chainguard runs as non-root automatically — no USER needed

# ✅ For other base images — run as non-root
USER nonroot
USER 1001

# ✅ Minimal COPY — only the artifact into the runtime stage
COPY --from=build /app/build/libs/*.jar app.jar

# ❌ Copies secrets, test files, .git, the whole build directory
COPY . .
```

Secrets are injected as env/secrets by NAIS at runtime — never in the Dockerfile.

## .dockerignore

Always create a `.dockerignore` so the build context stays small and secrets do not leak:

```
.git
.github
.idea
*.md
docker-compose*.yml
.env*
```

Add the stack's build output directories, and re-include the artifact path if the Dockerfile copies a CI-built artifact (e.g. `!build/libs`).

## CI — registry authentication

Use `nais/docker-build-push` in GitHub Actions — it handles authentication against Nav's Chainguard registry and pushes to GAR with SBOM and SLSA attestation. Build the artifact before the docker step.

## Boundaries

### Always
- Chainguard base images from Nav's registry, major matched against the build toolchain
- Build the artifact in CI (single-stage COPY) or multi-stage if the build happens in the Dockerfile
- A `.dockerignore` file
- Copy dependency manifests separately for layer caching
- `nais/docker-build-push` for CI

### Ask first
- Custom base images
- `--privileged` or extra Linux capabilities
- Mounting secrets during build

### Never
- `COPY . .` in the final stage
- Root user in production
- Secrets in the Dockerfile (`ENV SECRET=...`, `ARG PASSWORD=...`) — use NAIS secrets
- The `latest` tag on Nav registry images (use a specific major version)
- Full OS images (`ubuntu`, `debian`, `openjdk`)
- The build toolchain in the runtime image (use multi-stage or build the artifact in CI)

## Repository adapter

Facts for **this** repository (syfo-budstikka). Replace this section when rolling the core out to another repository.

- **This repository has no Dockerfile.** Images are built with Jib (ADR 0010) via `nais/login` in the deploy workflow. These standards apply only if a Dockerfile is introduced — prefer keeping Jib.
- JVM toolchain major lives in `gradle/libs.versions.toml` (`java`); a base image must match it.
- Runtime base image (through Jib) is the Chainguard `jre` from Nav's registry, configured in `build.gradle.kts`.
