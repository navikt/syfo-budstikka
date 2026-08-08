---
description: "Used when writing or changing a Dockerfile or .dockerignore for this Ktor backend — base image selection, multi-stage/fat-jar builds, non-root and security."
applyTo: "**/Dockerfile*, **/.dockerignore"
---

# Docker — Nav (Ktor backend)

Standards for Dockerfiles in this Ktor service (`no.nav.syfo`): Chainguard base images, fat jar from Gradle, non-root and security practice.

Reference: [Chainguard base images — sikkerhet.nav.no](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages)

## Base images — Chainguard

Nav pays for [Chainguard base images](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages) with minimal vulnerabilities. Use these instead of Google Distroless or full OS images.

### Nav's private registry (JVM)

```
europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/<image>:<tag>
```

Relevant images for this service: `jre` (runtime), `jdk` (builds inside the Dockerfile).

### Tags

- Use the major version that matches `jvmToolchain(...)` in `build.gradle.kts` (here: **25** → `jre:openjdk-25`). Chainguard does not backport.
- Do not pin SHAs manually. Set up a workflow for regular rebuilds.
- Use [digestabot](https://github.com/navikt/digestabot) if you want SHA pinning with automatic PRs.

```dockerfile
# ✅ Chainguard JRE from Nav's registry — match major against jvmToolchain
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25

# ⚠️ Google Distroless works, but Chainguard is preferred in Nav
FROM gcr.io/distroless/java21-debian12:nonroot

# ❌ Avoid full OS images
FROM ubuntu:22.04
FROM openjdk:25
```

## Fat jar from Gradle (recommended)

The Ktor plugin builds a fat jar with `./gradlew buildFatJar` → `build/libs/*-all.jar`. Build the jar in CI and copy it in — then a simple single-stage Dockerfile is enough, and the Gradle layer stays out of the image.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*-all.jar app.jar
# mainClass = io.ktor.server.netty.EngineMain is baked in by the Ktor plugin
CMD ["java", "-jar", "app.jar"]
```

`EXPOSE` is not required for NAIS, but can document the port (default Ktor/Netty: 8080).

## Multi-stage (building inside the Dockerfile)

Use only if you do not build the jar in CI. Then it must be multi-stage so the Gradle layer stays out of the runtime image.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25 AS build
WORKDIR /app
# Dependencies first → better layer caching (see below)
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew buildFatJar --no-daemon

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
ENV TZ="Europe/Oslo"
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

## Layer caching

```dockerfile
# ✅ Copy build scripts and the gradle wrapper first → cache holds while dependencies are unchanged
COPY build.gradle.kts settings.gradle.kts gradle.properties gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew buildFatJar --no-daemon

# ❌ Invalidates the cache on any source change
COPY . .
RUN ./gradlew buildFatJar
```

## Security

```dockerfile
# ✅ Chainguard runs as non-root automatically — no USER needed

# ✅ For other base images — run as non-root
USER nonroot
USER 1001

# ✅ Minimal COPY — only the jar into the runtime stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# ❌ Copies secrets, test files, .git, the whole build directory
COPY . .
```

Secrets (TokenX, Azure AD, Postgres, Kafka) are injected as env/secrets by NAIS at runtime — never in the Dockerfile.

## .dockerignore

Always create a `.dockerignore` so the build context stays small and secrets do not leak:

```
.git
.github
.gradle
.idea
build
!build/libs
*.md
docker-compose*.yml
.env*
```

> Note: if you copy the finished jar with `COPY build/libs/*-all.jar`, `build/libs` must not be excluded — use `!build/libs` as above.

## CI — Chainguard authentication

Use `nais/docker-build-push` in GitHub Actions — it handles authentication against Nav's Chainguard registry automatically. Build the jar before the docker step.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0
        with:
          distribution: temurin
          java-version: 25
      - run: ./gradlew buildFatJar
      - uses: nais/docker-build-push@v0
        id: docker-push
        with:
          team: team-esyfo
```

## Boundaries

### Always
- Chainguard `jre`/`jdk` from Nav's registry, major matched against `jvmToolchain`
- Fat jar via `./gradlew buildFatJar` (single-stage COPY) or multi-stage if the build happens in the Dockerfile
- A `.dockerignore` file
- Copy dependencies separately for layer caching
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
- The Gradle layer in the runtime image (use multi-stage or build the jar in CI)
