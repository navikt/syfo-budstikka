---
description: "Brukes når du skriver eller endrer en Dockerfile eller .dockerignore for denne Ktor-backenden — valg av base image, multi-stage/fat-jar-bygg, non-root og sikkerhet."
applyTo: "**/Dockerfile*, **/.dockerignore"
---

# Docker — Nav (Ktor-backend)

Standarder for Dockerfile i denne Ktor-tjenesten (`no.nav.syfo`): Chainguard base images, fat-jar fra Gradle, non-root og sikkerhetspraksis.

Reference: [Chainguard base images — sikkerhet.nav.no](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages)

## Base Images — Chainguard

Nav betaler for [Chainguard base images](https://sikkerhet.nav.no/docs/verktoy/chainguard-dockerimages) med minimale sårbarheter. Bruk disse i stedet for Google Distroless eller fulle OS-images.

### Navs private registry (JVM)

```
europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/<image>:<tag>
```

Relevante images for denne tjenesten: `jre` (kjøring), `jdk` (bygg i Dockerfile).

### Tags

- Bruk major version som matcher `jvmToolchain(...)` i `build.gradle.kts` (her: **25** → `jre:openjdk-25`). Chainguard backporter ikke.
- Ikke pin SHA manuelt. Sett opp workflow for regelmessig rebuild.
- Bruk [digestabot](https://github.com/navikt/digestabot) om du vil pinne SHA med automatiske PR-er.

```dockerfile
# ✅ Chainguard JRE fra Navs registry — match major mot jvmToolchain
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25

# ⚠️ Google Distroless fungerer, men Chainguard er foretrukket i Nav
FROM gcr.io/distroless/java21-debian12:nonroot

# ❌ Unngå fulle OS-images
FROM ubuntu:22.04
FROM openjdk:25
```

## Fat-jar fra Gradle (anbefalt)

Ktor-pluginen bygger en fat-jar med `./gradlew buildFatJar` → `build/libs/*-all.jar`. Bygg jaren i CI og kopier den inn — da holder en enkel single-stage Dockerfile, og du slipper Gradle-laget i image.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25
ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/libs/*-all.jar app.jar
# mainClass = io.ktor.server.netty.EngineMain bakes inn av Ktor-pluginen
CMD ["java", "-jar", "app.jar"]
```

`EXPOSE` er ikke nødvendig for NAIS, men kan dokumentere porten (default Ktor/Netty: 8080).

## Multi-stage (bygg i Dockerfile)

Bruk kun hvis du ikke bygger jaren i CI. Da skal det være multi-stage så Gradle-laget ikke havner i kjøre-imaget.

```dockerfile
FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jdk:openjdk-25 AS build
WORKDIR /app
# Avhengigheter først → bedre layer caching (se under)
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

## Layer Caching

```dockerfile
# ✅ Kopier build-script og gradle-wrapper først → cache holder så lenge avhengigheter er uendret
COPY build.gradle.kts settings.gradle.kts gradle.properties gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew buildFatJar --no-daemon

# ❌ Invaliderer cache ved enhver kildeendring
COPY . .
RUN ./gradlew buildFatJar
```

## Sikkerhet

```dockerfile
# ✅ Chainguard kjører som non-root automatisk — ingen USER nødvendig

# ✅ For andre base images — kjør som non-root
USER nonroot
USER 1001

# ✅ Minimal COPY — kun jaren til kjøre-stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# ❌ Kopierer secrets, testfiler, .git, hele build-mappa
COPY . .
```

Hemmeligheter (TokenX, Azure AD, Postgres, Kafka) injiseres som env/secrets via NAIS i runtime — aldri i Dockerfile.

## .dockerignore

Lag alltid en `.dockerignore` så build-konteksten holdes liten og secrets ikke lekker:

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

> Merk: hvis du kopierer den ferdige jaren med `COPY build/libs/*-all.jar` må `build/libs` ikke ekskluderes — bruk `!build/libs` som over.

## CI — Chainguard-autentisering

Bruk `nais/docker-build-push` i GitHub Actions — den håndterer autentisering mot Navs Chainguard-registry automatisk. Bygg jaren før docker-steget.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    steps:
      - uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11 # v4.1.1
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - run: ./gradlew buildFatJar
      - uses: nais/docker-build-push@v0
        id: docker-push
        with:
          team: team-esyfo
```

## Grenser

### Alltid
- Chainguard `jre`/`jdk` fra Navs registry, major matchet mot `jvmToolchain`
- Fat-jar via `./gradlew buildFatJar` (single-stage COPY) eller multi-stage hvis bygg skjer i Dockerfile
- `.dockerignore`-fil
- Kopier avhengigheter separat for layer caching
- `nais/docker-build-push` for CI

### Spør først
- Custom base images
- `--privileged` eller ekstra Linux capabilities
- Mounting secrets i build

### Aldri
- `COPY . .` i final stage
- Root-bruker i produksjon
- Secrets i Dockerfile (`ENV SECRET=...`, `ARG PASSWORD=...`) — bruk NAIS-secrets
- `latest`-tag på Nav registry images (bruk spesifikk major version)
- Fulle OS-images (`ubuntu`, `debian`, `openjdk`)
- Gradle-laget i kjøre-imaget (bruk multi-stage eller bygg jaren i CI)
