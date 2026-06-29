---
name: kotlin-ktor
description: "Bruk ved arbeid på Ktor-backend i no.nav.syfo: nye routes/plugins, ContentNegotiation, auth (TokenX/Azure AD, JWT-claims som NAVident), Koin DI, CallId/CallLogging/MDC, StatusPages/ApiError-feilkontrakt, paginering, input-validering, Postgres/Flyway/Kafka-oppsett, avhengigheter via version catalog — eller når noen sier /kotlin-ktor."
---

# Ktor — NAV-spesifikt (syfo-budstikka)

Kotlin + Ktor 3.x på Netty, pakke `no.nav.syfo`. Java 25, Gradle. Norsk er arbeidsspråk.

## Oppstart og moduler

Repoet bruker **config-basert oppstart** med `EngineMain`, ikke `embeddedServer { }`. Moduler registreres i `src/main/resources/application.yaml` under `ktor.application.modules`, ikke i `main.kt`:

```yaml
ktor:
  deployment:
    port: 8080
  application:
    modules:
      - no.nav.syfo.RoutingKt.configureRouting
      - no.nav.syfo.AppKt.apiModule
```

`main.kt` kaller bare `io.ktor.server.netty.EngineMain.main(args)`. En ny `Application.xxx()`-modul tas i bruk ved å legge fully-qualified `<Fil>Kt.<funksjon>`-referansen i listen over — å bare skrive funksjonen er ikke nok.

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

Detekter eksisterende DI-mønster først. Er `io.insert-koin` i avhengighetene: bruk Koin (`install(Koin) { modules(appModule) }`, hent via `by inject()`). Ellers er **manuell konstruktørinjeksjon** i `Application`-modulen default — ikke dra inn et DI-rammeverk uoppfordret. (Dette repoet har ingen Koin i dag.)

## Logging og sporing

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    verify { it.isNotBlank() }
}
install(CallLogging) {
    callIdMdc("x_request_id")
}
```

NAIS forventer strukturert (JSON) logging til stdout for innsamling. Logg aldri fnr eller særlige kategorier personopplysninger i klartekst — bruk callId/aktørreferanser i stedet.

## Feilhåndtering — StatusPages + ApiError

Team-standard for strukturerte feilresponser: sealed `ApiErrorException`-hierarki + `StatusPages`-plugin som mapper til en enhetlig `ApiError`-payload (status, type, message, path, timestamp). Se [references/error-handling.md](references/error-handling.md) for full implementasjon (`ErrorType`-enum, `ApiErrorException`-klasser, `installStatusPages()`, `determineApiError()`, logging).

## Paginering og input-validering

Team-standard `PaginatedResponse<T>`-wrapper og route-validering med tidlig-retur (kast `ApiErrorException.BadRequestException`) på ugyldige parametre. Se [references/paginering-og-validering.md](references/paginering-og-validering.md).

## Persistens (Postgres / Flyway)

- Flyway-migreringer i `src/main/resources/db/migration` (`V<n>__<navn>.sql`), kjøres ved oppstart. Migreringer er append-only — endre aldri en allerede deployet migrering.
- Bruk NAIS-provisjonert Postgres med IAM/Vault-rotert credential; ikke hardkod connection-string.
- Skjema- og lagringsvalg for personopplysninger er en arkitekturbeslutning → utløs ADR i `docs/adr/` via grill-fasen.

## Kafka (hendelseskonsument/-produsent)

- Konsumenter må være **idempotente** og tåle replay — dedup på nøkkel/offset, ikke anta exactly-once.
- Definer eksplisitt oppførsel når downstream er nede (retry/DLQ), og bekreft `accessPolicy`/topic-tilgang i NAIS.
- Logg med callId/hendelse-id, aldri rå payload med PII.

## Graceful shutdown

`EngineMain` (Netty) installerer shutdown-hook og håndterer `SIGTERM` automatisk — påbegynte kall fullføres før prosessen stopper. Du trenger ikke manuell readiness-toggling i applikasjonen. På plattformsiden gir NAIS `preStop`-hook og rimelig `terminationGracePeriodSeconds` tid til å drenere. Anti-mønstre: manuell `readiness=false`-vipping og for lav grace-period.

## Verifisering

Kvalitetsgater er deterministiske: `./gradlew test` og `./gradlew build`. Ktor-routes testes med `testApplication { }` (`ktorLibs.server.testHost`) — se `src/test/kotlin/ServerTest.kt`. Ingen «ser riktig ut»-påstand uten ferskt kommando-output + exit-kode.
