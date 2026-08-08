---
description: "Used when creating or changing GitHub Actions workflows (.github/workflows/**) in a Nav repository — build, test and Nais deploy. Trigger signals: SHA pinning, permissions, deploy dev/prod, secrets, concurrency, build caching. Portable core; repository facts live in the adapter section at the end."
applyTo: ".github/workflows/**"
---

# GitHub Actions — Nav

Standards for CI/CD workflows with GitHub Actions on Nais. The core is stack-agnostic; anything specific to this repository lives in the [repository adapter](#repository-adapter) at the end. Check whether the team has a repository with reusable workflows before writing your own.

## Action pinning

Pin all third-party actions to a full commit SHA with a version comment:

```yaml
# ✅ Pinned to SHA
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1

# ❌ An unpinned tag can be compromised
- uses: actions/checkout@v4
```

> **Exception**: `nais/*` actions (`nais/docker-build-push`, `nais/deploy`, `nais/login`) are internal Nav actions with stable semver tags. They do not need SHA pinning, but should have a version comment.

## Minimal permissions

```yaml
permissions:
  contents: read
  id-token: write   # required by nais/docker-build-push and nais/deploy (OIDC)

# ❌ Never
permissions: write-all
```

## Build, test and Nais deploy

The standard shape, independent of stack:

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
      # <stack setup and build steps — see the repository adapter>
      - uses: nais/docker-build-push@v0
        id: docker-build-push
        with:
          team: <team>

  deploy-dev:
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: nais/deploy/actions/deploy@v2
        env:
          CLUSTER: dev-gcp
          RESOURCE: <path to Nais manifest>
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
          RESOURCE: <path to Nais manifest>
          VAR: image=${{ needs.build.outputs.image }}
```

Deploy every environment from the same built image — never rebuild per environment.

## Build caching

Use the setup action's built-in dependency caching (`actions/setup-java` with `cache: gradle`, `actions/setup-node` with `cache: npm`, and so on) rather than hand-rolled `actions/cache` steps. The toolchain version in CI must match the version the repository's build defines. For integration tests that need infrastructure (databases, brokers) via Testcontainers — let the tests start containers themselves; do not define service containers in the workflow unless the team's pattern requires it.

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
- The CI toolchain version must match the repository's build definition
- Check for reusable workflows in the team before writing your own

### Ask first
- New secrets or environment variables (including TokenX/Azure AD related)
- Changes to deploy order (dev → prod)
- New reusable workflows or changes to the `team` value

### Never
- `permissions: write-all`
- Unpinned third-party action versions without SHA (exception: `nais/*` actions)
- Logging secrets in workflow output
- `pull_request_target` with `actions/checkout` of the PR branch (code injection)

## Repository adapter

Facts for **this** repository (syfo-budstikka). Replace this section when rolling the core out to another repository.

- Team: `team-esyfo`. Stack: Kotlin/Ktor backend built with Gradle (version catalogs `libs` and `ktorLibs`); pure backend, no Node/frontend steps.
- CI toolchain: `actions/setup-java` with `distribution: temurin`, `cache: gradle`, and `java-version` matching `java` in `gradle/libs.versions.toml`.
- **Images are built with Jib** (ADR 0010) via `nais/login` + `./gradlew jib`, not `nais/docker-build-push`. Every environment deploys the image from the single build job (`deploy.yaml` → `deploy-nais.yaml`).
- The PR/merge-queue gate is the reusable `ci-reusable.yml`, summarised by the single required `Merge gate` check. Contract-relevant changes additionally run the compatibility gates (`scripts/detect-contract-changes.sh`).
- Contract releases have their own tag-driven workflow (`publish-kontrakt.yml`).
- Lint workflows locally with `actionlint` and `zizmor` before pushing.
