---
name: kotlin-ktor
description: "Bruk ved Ktor-spesifikt arbeid i et NAV Kotlin-backend: routes, plugins, auth, DI/wiring, logging/MDC, StatusPages, validering og Ktor-relatert Kafka/Postgres-oppsett — eller /kotlin-ktor. Bruk /kotlin for ren Kotlin-kode uten Ktor-oppsett."
---

# Ktor — NAV-backend

Undersøk aktiv Ktor-versjon, engine, kildepakke, byggoppsett og språkpolicy før
du velger et mønster. Skillens eksempler er mekanismer, ikke repo-fakta.

## Skill-grenser

- Bruk `/kotlin-ktor` når endringen rører Ktor-rammeverket (routes, plugins, auth, app wiring).
- Bruk `/kotlin` når endringen er ren Kotlin (domene, typer, nullability, coroutines, ny kode eller refaktorering) uten Ktor-oppsett.
- Bruk `/unit-tests`, `/integration-tests` eller `/e2e-tests` for testtype-spesifikk flyt.

## Oppstart og moduler

Undersøk eksisterende entrypoint og Ktor-konfigurasjon før du endrer oppstart.
Ikke anta `application.yaml` fremfor `application.conf`, `EngineMain` fremfor
`embeddedServer`, eller et bestemt pakkenavn. Ved config-basert oppstart må den
fullt kvalifiserte modulreferansen matche kildefilen, og nye moduler legges til
med samme format som repoet allerede bruker.

## Avhengigheter (version catalogs)

To kataloger er i bruk:
- `ktorLibs` — alle Ktor-artefakter, pinnet via `io.ktor:ktor-version-catalog` i `settings.gradle.kts`. Bruk f.eks. `implementation(ktorLibs.server.auth)`, `ktorLibs.server.contentNegotiation`. Ikke skriv Ktor-versjoner manuelt.
- `libs` — alt annet (logback, Koin, db, Kafka, osv.), definert i `gradle/libs.versions.toml`.

Legg nye Ktor-plugins via `ktorLibs.*`; legg tredjepart via `libs.*` med versjon i `[versions]`/`[libraries]`.

## Autentisering (TokenX / Azure AD)

```kotlin
authenticate("azureAd") {
    get("/api/protected") {
        val principal = call.principal<JWTPrincipal>()
        val navIdent = principal?.getClaim("NAVident", String::class)
            ?: throw ApiErrorException.UnauthorizedException("Mangler NAVident")
    }
}
```

- **TokenX** for borger-til-app (on-behalf-of sluttbruker, ID-porten-opphav). Valider `sub`/`pid`.
- **Azure AD** for ansatt-flyt og maskin-til-maskin internt. NAVident-claim identifiserer saksbehandler.
- Sett opp `accessPolicy.inbound/outbound` i NAIS-manifestet for hvilke apper som får kalle/kalles. Auth-valg er typisk en blind-spot — grav i det i grill-fasen.

## Avhengighetsinjeksjon

Detekter eksisterende DI-mønster først og behold det. Ktor
`DependencyRegistry`, Koin og manuell konstruktørinjeksjon har ulike idiomer;
ikke innfør et nytt DI-rammeverk eller anta ett av dem uten bevis i aktiv kode.

## Logging og sporing

Les repoets dokumenterte korrelasjonsmodell før du legger til en identifikator.
En request-ID kan korrelere et synkront HTTP-hopp, men er ikke ende-til-ende over
Kafka- eller databasebaserte asynkrone grenser uten persistens. Bruk Ktor
`CallId` bare når en etablert synkron kontrakt krever det, og scope MDC slik at
verdier ryddes etter kallet. For asynkrone flyter brukes repoets persisterte
forretnings-ID-er; W3C trace context dekker normalt hvert tekniske hopp.

NAIS forventer strukturert (JSON) logging til stdout for innsamling. Logg aldri
fnr eller særlige kategorier personopplysninger i klartekst — bruk bare trygge,
dokumenterte korrelasjonsfelt.

## Feilhåndtering — StatusPages + ApiError

Team-standard for strukturerte feilresponser: sealed `ApiErrorException`-hierarki + `StatusPages`-plugin som mapper til en enhetlig `ApiError`-payload (status, type, message, path, timestamp). Se [references/error-handling.md](references/error-handling.md) for full implementasjon (`ErrorType`-enum, `ApiErrorException`-klasser, `installStatusPages()`, `determineApiError()`, logging).

## Paginering og input-validering

Team-standard `PaginatedResponse<T>`-wrapper og route-validering med tidlig-retur (kast `ApiErrorException.BadRequestException`) på ugyldige parametre. Se [references/paginering-og-validering.md](references/paginering-og-validering.md).

## Utgående HttpClient (kall mot nedstrøms-tjeneste)

Når backenden selv kaller en nedstrøms-tjeneste: bruk Ktor `HttpClient` via
`ktorLibs.client.*`, med eksplisitt timeout/retry, korrelasjon etter den
etablerte synkrone kontrakten og oversettelse av nedstrøms-feil til repoets
feilkontrakt. Token for kallet hentes som beskrevet i `/auth-overview` (TokenX
OBO / Azure AD M2M) — ikke dupliser auth her. Se
[references/http-client.md](references/http-client.md) for konkret oppsett;
circuit breaker krever Resilience4j og finnes ikke native i Ktor.

## Persistens (Postgres / Flyway)

- Flyway-migreringer i `src/main/resources/db/migration` (`V<n>__<navn>.sql`), kjøres ved oppstart. Migreringer er append-only — endre aldri en allerede deployet migrering.
- Bruk NAIS-provisjonert Postgres med IAM/Vault-rotert credential; ikke hardkod connection-string.
- Review skjema- og lagringsvalg for personopplysninger med
  `/nav-architecture-review`. Når valget passerer ADR-gaten, anbefal
  dokumentert løp og vent på brukerens valg før `/domain-modeling` registrerer
  det.

## Kafka (hendelseskonsument/-produsent)

- Konsumenter må være **idempotente** og tåle replay — dedup på nøkkel/offset, ikke anta exactly-once.
- Definer eksplisitt oppførsel når downstream er nede (retry/DLQ), og bekreft `accessPolicy`/topic-tilgang i NAIS.
- Logg med repoets trygge korrelasjonsfelt, aldri rå payload med PII.

## Graceful shutdown

`EngineMain` (Netty) installerer shutdown-hook og håndterer `SIGTERM` automatisk — påbegynte kall fullføres før prosessen stopper. Du trenger ikke manuell readiness-toggling i applikasjonen. På plattformsiden gir NAIS `preStop`-hook og rimelig `terminationGracePeriodSeconds` tid til å drenere. Anti-mønstre: manuell `readiness=false`-vipping og for lav grace-period.

## Verifisering

Kvalitetsgater er deterministiske: `./gradlew test` og `./gradlew build`. Ktor-routes testes med `testApplication { }` (`ktorLibs.server.testHost`) — se `src/test/kotlin/ServerTest.kt`. Ingen «ser riktig ut»-påstand uten ferskt kommando-output + exit-kode.
