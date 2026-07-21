---
description: "Brukes ved alt Kotlin-arbeid i dette Ktor-backend-repoet (no.nav.syfo): routes/plugins, JWT-claims, DI, logging, feilhåndtering, Gradle Version Catalog, Flyway, Kafka, metrikker og tester."
applyTo: "**/*.kt"
---

# Kotlin — Nav Ktor-backend (no.nav.syfo)

Denne fila er kort og bindende: harde regler som alltid gjelder for `*.kt`.
Detaljert arbeidsflyt ligger i skills:

- `/kotlin` for ny Kotlin-kode **og** refaktorering (idiomatisk Kotlin, typer, coroutines, design).
- `/kotlin-ktor` for Ktor-spesifikt arbeid (routes/plugins/auth/StatusPages/wiring).
- `/unit-tests`, `/integration-tests`, `/e2e-tests` for riktig testnivå.

## Harde regler

1. **Navngiving:** norske ord kun på domeneord. Teknisk mekanikk på engelsk.
   - Domeneord som forblir norske: `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, `Brev`.
   - Vanlige tekniske navn skal være engelske: `save`, `fetch`, `isDead`, `toColumns`.
   - Norske ord i publiserte kontrakter er breaking å endre.

2. **Arkitektur:** følg hexagonal modell.
   - `domain` er uavhengig.
   - `application` avhenger av `domain`.
   - `infrastructure` implementerer porter.

3. **Sikkerhet og persondata:**
   - Ingen fnr, tokens eller annen PII i logger eller feilmeldinger.
   - Ingen hardkodede secrets, URL-er eller auth-verdier i kode.
   - Valider auth-config (`issuer`/`audience`) via konfigurasjon.

4. **Avhengigheter og konfigurasjon:**
   - Bruk version catalogs: `ktorLibs.*` for Ktor, `libs.*` for øvrige avhengigheter.
   - Ikke hardkod versjoner i `build.gradle.kts`.
   - Bruk Ktor-mønstre i repoet, ikke Spring-mønstre.

5. **Datatilgang og meldingsflyt:**
   - Flyway er append-only: nye `V<n>__...sql`, aldri endre deployet migrering.
   - SQL skal være parameterisert.
   - Kafka-konsum må være idempotent.

## Validering

- Kjør minste relevante testkommando underveis.
- Før ferdigmelding: minst `./gradlew test`.
- Ved bredere eller risikofylt endring: `./gradlew build`.
