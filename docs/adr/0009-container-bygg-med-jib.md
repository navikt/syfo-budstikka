# ADR 0009 — Container-bygg med Jib i stedet for Dockerfile

- Status: besluttet (dev-POC, cutover etter verifisert dev-runde)
- Dato: 2026-07-22
- Relatert: `.github/instructions/docker.instructions.md` (Chainguard-krav),
  `.github/instructions/github-actions.instructions.md` (SHA-pinning, permissions),
  `.github/workflows/deploy.yaml`, `build.gradle.kts`

## Kontekst

Container-bygget skjer i dag via `Dockerfile` (Chainguard `jre:openjdk-25`, fat-jar) og
`nais/docker-build-push` i deploy-workflowen. Vi vil bygge image fra Gradle uten en egen
`Dockerfile`. Ktor-pluginens `docker { }`-tasks (`setupJibLocal`) bruker `Task.project` ved
kjøretid, som Gradle 10 gjør til en hard feil — en oppgraderingsrisiko vi ikke eier selv.

Chainguard er eneste tillatte base image. `nais/docker-build-push` gjør i tillegg SLSA-
attestering + cosign-signering automatisk (`salsa: true`); en naiv Jib-flyt uten dette ville
vært en stille supply-chain-regresjon.

## Beslutning

Bygg image med **ren Jib** (Ktor-pluginen aktiverer allerede JibPlugin), ikke Ktor sine
`docker { }`-tasks:

1. **`build.gradle.kts`:** konfigurer `jib { from / to / container }` direkte (via `JibExtension`)
   og fjern `ktor { docker { } }`-blokka. Å sette Chainguard-basen (JRE 25) eksplisitt i
   `from.image` fjerner Ktor-pluginens JRE-validering og `setupJibLocal`-stien. En `Task.project`-
   deprecation gjenstår i jib-gradle-pluginens egne tasks (upstream, ikke vår kode; akseptert).
   Basen kan ikke hentes registry-direkte: Jib feiler på Chainguards OCI Image Index v1.1
   (`artifactType`, verifisert), så vi bruker `docker://`-referanse + et `docker pull`-pre-pull-steg.

2. **deploy-workflow:** `nais/login@v0` → `./gradlew jib` (direkte push til GAR) → les digest
   fra `build/jib-image.digest` → `nais/attest-sign` på digesten. Image-path bygges fra
   `nais/login`-outputen `registry` (ikke hardkodet management-project-id). Tag =
   `YYYY.MM.DD-HH.mm-<kort-sha>` for kronologi + commit-sporbarhet. Tredjeparts-actions
   SHA-pinnes (unntak `nais/*`).

3. **PR-gate (`build.yml`):** legg til `./gradlew jibBuildTar` (bygg uten push) for å fange
   base-image-/Jib-konfigfeil i PR i stedet for på main-deploy.

`Dockerfile` beholdes som rollback i POC-perioden og fjernes først når dev-deploy er stabil,
Chainguard-base er bekreftet i bygget image, og signert image deployer uten regresjon.

## Konsekvenser

- Ingen `Dockerfile` å vedlikeholde; base/JRE/jvmFlags styres ett sted i Gradle.
- Direkte Jib-push fjerner docker-daemon-avhengigheten i CI (med mindre OCI 1.1-fallbacken
  trengs), og gir en stabil digest for signering.
- SLSA-attestering + signering bevares eksplisitt — ingen supply-chain-regresjon.
- Ny risiko: Jib-oppgraderinger og Chainguard-manifestformat kan bryte bygget; PR-gatens
  `jibBuildTar` fanger dette tidlig.
