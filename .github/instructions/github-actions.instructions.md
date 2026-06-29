---
description: "Brukes når du oppretter eller endrer GitHub Actions-workflows (.github/workflows/**) for dette Ktor-backendet — build, test, docker-build-push og Nais-deploy. Trigger-signaler: SHA-pinning, permissions, deploy dev/prod, secrets, concurrency, Gradle-caching."
applyTo: ".github/workflows/**"
---

# GitHub Actions — syfo-budstikka (Ktor-backend, team-esyfo)

Standarder for CI/CD-workflows med GitHub Actions på Nais for dette Kotlin/Ktor-backendet (`no.nav.syfo`). Sjekk om `team-esyfo` har et repo med gjenbrukbare workflows før du skriver egne.

Dette repoet bygges med Gradle (version catalogs: `libs`, `ktorLibs`), kjører på Netty via `io.ktor.server.netty.EngineMain` og deployes som Nais-app. Det er et rent backend — ingen Node/frontend-steg i workflowen.

## Action Pinning

Pin alle tredjeparts-actions til full commit SHA med versjonskommentar:

```yaml
# ✅ Pinnet til SHA
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
- uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0

# ❌ Upinnet tag kan kompromitteres
- uses: actions/checkout@v4
```

> **Unntak**: `nais/*`-actions (`nais/docker-build-push`, `nais/deploy`) er interne Nav-actions med stabile semver-tags. Disse trenger ikke SHA-pinning, men bør ha versjonskommentar.

## Minimale tilganger

```yaml
permissions:
  contents: read
  id-token: write   # kreves for nais/docker-build-push og nais/deploy (OIDC)

# ❌ Aldri
permissions: write-all
```

## Build, test og Nais-deploy

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
          java-version: 25      # samsvar med jvmToolchain(25) i build.gradle.kts
          cache: gradle
      - run: ./gradlew build --no-daemon
      - uses: nais/docker-build-push@v0
        id: docker-build-push
        with:
          team: team-esyfo

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

Nais-manifestet (`RESOURCE`) skal følge mønsteret i `kotlin-ktor`-/`nais-manifest`-skillet. Endrer du ressurser, helse-probes eller scaling der, oppdater manifestet samtidig.

## Gradle-caching

```yaml
- uses: actions/setup-java@387ac29b308b003ca37ba93a6cab5eb57c8f5f93 # v4.0.0
  with:
    distribution: temurin
    java-version: 25
    cache: gradle
```

`cache: gradle` cacher `~/.gradle/caches` og wrapper. Bruk `./gradlew --no-daemon` i CI for forutsigbar oppførsel. For migrasjons-/integrasjonstester med Postgres (Flyway/Kafka via Testcontainers) — la testene starte containere selv, ikke definer service-containere i workflowen med mindre teamets mønster krever det.

## Sikkerhet

```yaml
# Scanning av filsystem / avhengigheter
- uses: aquasecurity/trivy-action@0.28.0   # pin SHA i produksjon
  with:
    scan-type: fs
    severity: HIGH,CRITICAL
    exit-code: 1

# Statisk analyse av selve workflows
- run: pipx run zizmor .github/workflows/
```

## Grenser

### Alltid
- Pin tredjeparts-actions til SHA med versjonskommentar
- Sett eksplisitte `permissions` per workflow/job (`contents: read`, `id-token: write` for Nais)
- Sett `timeout-minutes` på alle jobs
- Bruk `concurrency` for deploy-workflows
- `java-version` i CI skal matche `jvmToolchain` i `build.gradle.kts`
- Sjekk gjenbrukbare workflows i team-esyfo før du skriver egen

### Spør først
- Nye secrets eller environment variables (også TokenX/Azure AD-relaterte)
- Endringer i deploy-rekkefølge (dev → prod)
- Nye gjenbrukbare workflows eller endringer i `team`-verdi

### Aldri
- `permissions: write-all`
- Upinnede tredjeparts-action-versjoner uten SHA (unntak: `nais/*`-actions)
- Logg secrets i workflow-output
- `pull_request_target` med `actions/checkout` av PR-branch (code injection)
