---
description: "Brukes ved alt Kotlin-arbeid i dette Ktor-backend-repoet (no.nav.syfo): routes/plugins, JWT-claims, DI, logging, feilhåndtering, Gradle Version Catalog, Flyway, Kafka, metrikker og tester."
applyTo: "**/*.kt"
---

# Kotlin — Nav Ktor-backend (no.nav.syfo)

Stack: Kotlin/JVM på Ktor + Netty (`io.ktor.server.netty.EngineMain`), config-drevet via `application.yaml`/`.conf`. Avhengigheter via Gradle Version Catalog (`gradle/libs.versions.toml`, `ktorLibs`). Kjører på NAIS.

## Grunnregler

- Avhengigheter legges til i `gradle/libs.versions.toml` og refereres som `libs.*` / `ktorLibs.*` — aldri hardkodede versjoner i `build.gradle.kts`.
- Bevar eksisterende kodestruktur og mønstre. Endre kun det oppgaven krever. Blir diffen uforholdsmessig stor mot oppgavens omfang — stopp og forklar før du fortsetter. Ikke refaktorer på siden.
- Sjekk hvilke plugins/avhengigheter som faktisk finnes før du foreslår et mønster. Repoet er Ktor — bruk Ktor-idiomer, ikke Spring.

## Konfigurasjon og oppstart

- Hemmeligheter og miljøavhengig config kommer fra miljøvariabler injisert av NAIS, lest via Ktor `ApplicationConfig` / `application.yaml`. Ikke hardkod URL-er, secrets eller `issuer`/`audience`.
- Organiser oppsett i `Application.*Module()`-funksjoner (f.eks. `fun Application.apiModule()`) som installerer plugins og registrerer routes.
- Graceful shutdown håndteres av Ktor/Netty via shutdown-hook på `SIGTERM`. Ikke implementer manuell `readiness=false`-toggling eller kort `terminationGracePeriodSeconds` — NAIS `preStop` dekker drenering.

## Autentisering (TokenX / Azure AD)

JWT-validering via `Authentication`-plugin. NAVident hentes fra claim:

```kotlin
authenticate("azureAd") {
    get("/api/protected") {
        val principal = call.principal<JWTPrincipal>()
        val navIdent = principal?.getClaim("NAVident", String::class)
    }
}
```

- Valider `issuer` og `audience` mot config. Test auth med MockOAuth2Server, ikke ekte tokens.

## Avhengighetsinjeksjon

Manuell konstruktørinjeksjon i `Module`-funksjonen er fint for små apper. Bruk Koin kun hvis `io.insert-koin` allerede er i avhengighetene — ikke dra inn et DI-rammeverk uoppfordret.

## Logging og MDC

Logback er logge-backend. Bruk strukturert logging (`KotlinLogging` / `kv()`-felter der mønsteret finnes). Propager korrelasjons-ID via CallId + MDC:

```kotlin
install(CallLogging) {
    mdc("x_request_id") { call.request.header("X-Request-Id") ?: UUID.randomUUID().toString() }
}
```

Aldri logg fødselsnummer, tokens eller andre personopplysninger i klartekst.

## Feilhåndtering — StatusPages + ApiError

Team-standard: et `sealed`-hierarki av `ApiErrorException` + `StatusPages`-plugin som mapper alt til én enhetlig `ApiError`-payload (`status`, `type`, `message`, `path`, `timestamp`). Kast typede unntak fra routes/services; la `StatusPages` oversette til HTTP-respons. Logg `ApiErrorException` som `warn`, alt annet som `error`.

```kotlin
fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val apiError = determineApiError(cause, call.request.path())
            if (cause is ApiErrorException) call.application.log.warn("callId=${call.callId}", cause)
            else call.application.log.error("callId=${call.callId}", cause)
            call.respond(apiError.status, apiError)
        }
    }
}
```

## Routes — validering og paginering

- Valider input tidlig med tidlig-retur / typede unntak (`throw ApiErrorException.BadRequestException(...)`).
- Bruk en felles `PaginatedResponse<T>`-wrapper for lister; tak på sidestørrelse (f.eks. maks 100).
- Sett `Location`-header på 201 Created.

```kotlin
post("/api/v1/vedtak") {
    val request = call.receive<CreateVedtakRequest>()
    if (request.brukerId.isBlank()) throw ApiErrorException.BadRequestException("brukerId kan ikke være tom")
    val vedtak = vedtakService.create(request)
    call.response.header("Location", "/api/v1/vedtak/${vedtak.id}")
    call.respond(HttpStatusCode.Created, vedtak.toDto())
}
```

## Database (Postgres / Flyway)

- Skjemaendringer kun via Flyway-migreringer i `src/main/resources/db/migration` (`V<n>__beskrivelse.sql`). Aldri endre en allerede kjørt migrering.
- Parameteriserte spørringer alltid — aldri streng-interpolasjon av brukerinput i SQL.
- HikariCP for connection pooling med fornuftige grenser.

## Kafka

- Idempotent konsumering: håndter at samme melding kan komme flere ganger.
- Commit offset først etter vellykket prosessering. Logg `topic`, `partition`, `offset` (ikke nyttelast med persondata).

## Metrikker

Micrometer med Prometheus-registry, eksponert på `/metrics` for NAIS-scraping.

## Tester

- `ktor-server-test-host` + `testApplication { }` for route-/integrasjonstester.
- MockOAuth2Server for auth, Testcontainers for Postgres/Kafka der det trengs.
- Kjør `./gradlew test` lokalt før du melder ferdig.
