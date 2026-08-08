---
description: "Used when creating or changing GitHub Actions workflows (.github/workflows/**) for this Ktor backend — build, test, docker-build-push and Nais deploy. Trigger signals: SHA pinning, permissions, deploy dev/prod, secrets, concurrency, Gradle caching."
applyTo: ".github/workflows/**"
---

# GitHub Actions — syfo-budstikka (Ktor backend, team-esyfo)

Standards for CI/CD workflows with GitHub Actions on Nais for this Kotlin/Ktor backend (`no.nav.syfo`). Check whether `team-esyfo` has a repository with reusable workflows before writing your own.

This repository builds with Gradle (version catalogs: `libs`, `ktorLibs`), runs on Netty via `io.ktor.server.netty.EngineMain` and deploys as a Nais app. It is a pure backend — no Node/frontend steps in the workflow.

## Action pinning

Pin all third-party actions to a full commit SHA with a version comment:

```yaml
# ✅ Pinned to SHA
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
- uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0

# ❌ An unpinned tag can be compromised
- uses: actions/checkout@v4
```

> **Exception**: `nais/*` actions (`nais/docker-build-push`, `nais/deploy`) are internal Nav actions with stable semver tags. They do not need SHA pinning, but should have a version comment.

## Minimal permissions

```yaml
permissions:
  contents: read
  id-token: write   # required by nais/docker-build-push and nais/deploy (OIDC)

# ❌ Never
permissions: write-all
```

## Build, test and Nais deploy

```yaml
name: Build and deploy
on:
  push:
    branches: [main]

permissions:
  contents: read
  id-token: write

concurrency:
  group: deploy-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    outputs:
      image: ${{ steps.docker-build-push.outputs.image }}
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0
        with:
          distribution: temurin
          java-version: 25      # match jvmToolchain(25) in build.gradle.kts
          cache: gradle
      - run: ./gradlew build --no-daemon
      - uses: nais/docker-build-push@v0
        id: docker-build-push
        with:
          team: team-esyfo
          identity_provider: ${{ secrets.NAIS_WORKLOAD_IDENTITY_PROVIDER }}
          project_id: ${{ vars.NAIS_MANAGEMENT_PROJECT_ID }}

  deploy-dev:
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: nais/deploy/actions/deploy@v2
        env:
          CLUSTER: dev-gcp
          RESOURCE: .nais/nais.yaml
          VAR: image=${{ needs.build.outputs.image }}

  deploy-prod:
    needs: [build, deploy-dev]
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: nais/deploy/actions/deploy@v2
        env:
          CLUSTER: prod-gcp
          RESOURCE: .nais/nais.yaml
          VAR: image=${{ needs.build.outputs.image }}
```

The Nais manifest (`RESOURCE`) must follow the pattern in the `kotlin-ktor`/`nais-manifest` skill. If you change resources, health probes or scaling there, update the manifest at the same time.

## Gradle caching

```yaml
- uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0
  with:
    distribution: temurin
    java-version: 25
    cache: gradle
```

`cache: gradle` caches `~/.gradle/caches` and the wrapper. Use `./gradlew --no-daemon` in CI for predictable behaviour. For migration/integration tests with Postgres (Flyway/Kafka via Testcontainers) — let the tests start containers themselves; do not define service containers in the workflow unless the team's pattern requires it.

## Security

```yaml
# Filesystem / dependency scanning
- uses: aquasecurity/trivy-action@0.28.0   # pin SHA in production
  with:
    scan-type: fs
    severity: HIGH,CRITICAL
    exit-code: 1

# Static analysis of the workflows themselves
- run: pipx run zizmor .github/workflows/
```

## Boundaries

### Always
- Pin third-party actions to SHA with a version comment
- Set explicit `permissions` per workflow/job (`contents: read`, `id-token: write` for Nais)
- Set `timeout-minutes` on all jobs
- Use `concurrency` for deploy workflows
- `java-version` in CI must match `jvmToolchain` in `build.gradle.kts`
- Check for reusable workflows in team-esyfo before writing your own

### Ask first
- New secrets or environment variables (including TokenX/Azure AD related)
- Changes to deploy order (dev → prod)
- New reusable workflows or changes to the `team` value

### Never
- `permissions: write-all`
- Unpinned third-party action versions without SHA (exception: `nais/*` actions)
- Logging secrets in workflow output
- `pull_request_target` with `actions/checkout` of the PR branch (code injection)
