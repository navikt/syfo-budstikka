---
name: kotlin-ktor
description: "Use for Ktor-specific work in no.nav.budstikka: routes, plugins, auth, DI/wiring, logging/MDC, StatusPages, validation, and Ktor-related Kafka/Postgres setup — or /kotlin-ktor."
---

# Ktor — NAV-specific (this repository)

Kotlin + Ktor 3.x on Netty, package `no.nav.budstikka` (the Gradle `group` is still `no.nav.syfo`). Java 25, Gradle. Language follows `docs/agents/language-policy.md`: documentation, agent-facing material and technical code identifiers are English, while user-facing text and API response messages are Norwegian Bokmål — as are the established Norwegian domain identifiers (`Brukervarsel`, `Ledervarsel`, `Brev`).

## Skill boundaries

- Use `/kotlin-ktor` when the change touches the Ktor framework (routes, plugins, auth, app wiring).
- Use `/integration-tests` or `/e2e-tests` for test-type-specific flows.

## Startup and modules

This repository uses **config-based startup** with `EngineMain`, not `embeddedServer { }`. Modules are registered in `src/main/resources/application.conf` (HOCON) under `ktor.application.modules`, not in `main.kt`:

```hocon
ktor {
  deployment {
    port = 8080
  }
  application {
    modules = [ no.nav.budstikka.ApplicationKt.module ]
  }
}
```

`main.kt` only calls `io.ktor.server.netty.EngineMain.main(args)`. A new `Application.xxx()` module is put into use by adding the fully-qualified `<File>Kt.<function>` reference to the list above — merely writing the function is not enough.

## Dependencies (version catalogs)

Two catalogs are in use:
- `ktorLibs` — all Ktor artifacts, pinned via `io.ktor:ktor-version-catalog` in `settings.gradle.kts`. Use e.g. `implementation(ktorLibs.server.auth)`, `ktorLibs.server.contentNegotiation`. Do not write Ktor versions by hand.
- `libs` — everything else (logback, Koin, db, Kafka, etc.), defined in `gradle/libs.versions.toml`.

Add new Ktor plugins via `ktorLibs.*`; add third-party libraries via `libs.*` with the version in `[versions]`/`[libraries]`.

## Authentication (TokenX / Azure AD)

```kotlin
authenticate("azureAd") {
    get("/api/protected") {
        val principal = call.principal<JWTPrincipal>()
        val navIdent = principal?.getClaim("NAVident", String::class)
            ?: throw ApiErrorException.UnauthorizedException("Mangler NAVident")
    }
}
```

- **TokenX** for citizen-to-app (on-behalf-of the end user, originating from ID-porten). Validate `sub`/`pid`.
- **Azure AD** for employee flows and internal machine-to-machine. The NAVident claim identifies the case worker.
- Set up `accessPolicy.inbound/outbound` in the NAIS manifest for which apps may call and be called. Auth choices are typically a blind spot — dig into it during the grilling phase.

## Dependency injection

Detect the existing DI pattern first. If `io.insert-koin` is among the dependencies: use Koin (`install(Koin) { modules(appModule) }`, resolve via `by inject()`). Otherwise **manual constructor injection** in the `Application` module is the default — do not pull in a DI framework unprompted. (This repository has no Koin today.)

## Logging and tracing

```kotlin
install(CallId) {
    retrieve { it.request.headers[NAV_CALL_ID_HEADER] }   // "Nav-Call-Id", see api/Plugins.kt
    generate { UUID.randomUUID().toString() }
    verify { callId: String -> callId.isNotEmpty() }
    header(NAV_CALL_ID_HEADER)
}
```

NAIS expects structured (JSON) logging to stdout for collection. Never log national identity numbers or special categories of personal data in plain text — use callId/actor references instead.

## Error handling — StatusPages + ApiError

Team standard for structured error responses: a sealed `ApiErrorException` hierarchy + the `StatusPages` plugin that maps to a uniform `ApiError` payload (status, type, message, path, timestamp). See [references/error-handling.md](references/error-handling.md) for the full implementation (`ErrorType` enum, `ApiErrorException` classes, `installStatusPages()`, `determineApiError()`, logging).

## Pagination and input validation

Team-standard `PaginatedResponse<T>` wrapper and route validation with early return (throw `ApiErrorException.BadRequestException`) on invalid parameters. See [references/pagination-and-validation.md](references/pagination-and-validation.md).

## Outgoing HttpClient (calls to a downstream service)

When the backend itself calls a downstream service: use the Ktor `HttpClient` via `ktorLibs.client.*`, with explicit timeout/retry, `Nav-Call-Id` propagation and translation of downstream errors into the repository's error contract. The token for the call is obtained as described in `/auth-overview` (TokenX OBO / Azure AD M2M) — do not duplicate auth here. See [references/http-client.md](references/http-client.md) for the concrete setup (engine, `HttpTimeout`, `HttpRequestRetry`, callId header, `ApiErrorException` mapping; circuit breaker requires Resilience4j — not native in Ktor).

## Persistence (Postgres / Flyway)

- Flyway migrations in `src/main/resources/database.migration` (`V<n>__<name>.sql`), configured via `.locations("classpath:database.migration")` and run at startup. Migrations are append-only — never change a migration that has already been deployed.
- Use NAIS-provisioned Postgres with an IAM/Vault-rotated credential; do not hardcode the connection string.
- Review schema and storage choices for personal data with
  `/architecture-review`. When the choice passes the ADR gate, recommend the
  documented route and wait for the user's choice before `/domain-modeling`
  records it.

## Kafka (event consumer/producer)

- Consumers must be **idempotent** and tolerate replay — dedupe on key/offset, do not assume exactly-once.
- Define explicit behavior when downstream is unavailable (retry/DLQ), and confirm `accessPolicy`/topic access in NAIS.
- Log with callId/event id, never a raw payload containing PII.

## Graceful shutdown

`EngineMain` (Netty) installs a shutdown hook and handles `SIGTERM` automatically — calls already in flight complete before the process stops. You do not need manual readiness toggling in the application. On the platform side, the NAIS `preStop` hook and a reasonable `terminationGracePeriodSeconds` give time to drain. Anti-patterns: manually flipping `readiness=false`, and too low a grace period.

## Verification

Quality gates are deterministic: `./gradlew test` and `./gradlew build`. Ktor routes are tested with `testApplication { }` / `TestApplication { }` (`ktorLibs.server.testHost`) — see `src/test/kotlin/no/nav/budstikka/bootstrap/DeadLetterReplayTest.kt`. No "looks right" claim without fresh command output + exit code.
