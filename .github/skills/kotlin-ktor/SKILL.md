---
name: kotlin-ktor
description: "Guide Ktor-specific work in no.nav.budstikka. Use when changing application modules, routes, CallId, DependencyRegistry, health and metrics, configuration, or Ktor clients; scoped Kotlin instructions cover every Kotlin edit."
---

# Ktor in syfo-budstikka

Ktor 3.x on Netty, Kotlin packages under `no.nav.budstikka`, Java 25, and Gradle.
Always start from the actual code. This skill describes the repository’s current
state and extension boundaries.

## Scope boundaries

- Use `/kotlin-ktor` for Ktor modules, routes, plugins, configuration, and wiring.
- `.github/instructions/kotlin.instructions.md` always applies to pure Kotlin
  domain and application code; do not route that work through a generic skill.
- Use `/unit-tests`, `/integration-tests`, or `/e2e-tests` for test workflow.
- Use `/auth-overview` before designing inbound auth or a new token flow.

## Startup

The repository uses configuration-based `EngineMain`.
`src/main/resources/application.conf` registers one production module:

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

`Application.kt` has a zero-argument `Application.module()` that delegates to
`configureApplication(overrides)`. The latter is the test/local seam and wires
plugins, Ktor DI, metrics, migration, Kafka consumers, workers, and internal
routes. Keep `module()` overload-free because Ktor resolves it by name.

## Dependency injection and modules

The repository uses Ktor `DependencyRegistry`, not Koin. Register dependencies
in `installDependencyInjection()` or a `DependencyRegistry` module function
(`databaseModule`, `clientModule`, `kafkaModule`, `workerModule`). Consume them
with `val dependency: Type by dependencies`.

For tests and the local application, override concrete bindings through
`configureApplication { ... }`. Keep construction in bootstrap/infrastructure
and ports in the application layer; global singletons must not become an
alternative container.

## Plugins and internal routes

`api/Plugins.kt` currently installs `CallId` with `Nav-Call-Id`.
`infrastructure/metrics/MetricsPlugin.kt` installs `MicrometerMetrics`.
`api/InternalApi.kt` exposes:

- `/internal/health/is_alive`
- `/internal/health/is_ready`
- `/internal/metrics`

Keep these paths aligned with `nais/nais-*.yaml`. Internal probe and scrape
routes must remain small and without inbound auth.

The repository does not yet have general `Authentication`, `ContentNegotiation`,
`StatusPages`, or `CallLogging`. Introduce them only when a concrete route
requires them, updating dependencies, plugin wiring, tests, and security
contract in the same vertical slice. Treat
[references/error-handling.md](references/error-handling.md) and
[references/pagination-and-validation.md](references/pagination-and-validation.md)
as design patterns, not descriptions of installed code.

## Auth and outbound clients

The current state has Texas-based Entra ID token retrieval for outbound calls,
but no inbound `authenticate {}` route. Do not infer TokenX or Azure AD inbound
auth from a manifest name alone. For new inbound auth, clarify caller, issuer,
audience, claims, and `accessPolicy` through `/auth-overview`. Recommend the
manual Grill with docs workflow and wait for the user to select it; never
invoke it automatically.

Existing clients live in `infrastructure/client/` and use Ktor `HttpClient`.
Follow their timeout, token, serialisation, and `Nav-Call-Id` patterns before
abstracting a new client. Read [references/http-client.md](references/http-client.md)
when a new client needs retries or an explicit error contract.

## Dependencies

`gradle/libs.versions.toml` is the catalog used by `build.gradle.kts`;
`settings.gradle.kts` additionally exposes Ktor’s official `ktorLibs`. Follow
this codebase’s established `libs.ktor.*` style and keep the Ktor version
aligned if the catalogs are consolidated. Never hard-code a third Ktor version.

## Persistence and Kafka

- Flyway files live in `src/main/resources/database.migration/` and run through
  `DataSource.migrate()` at startup. Use `/flyway-migration`.
- Kafka uses plain Apache clients under `infrastructure/kafka/`. Consumers must
  tolerate replay, and changed event contracts go through `/kafka-topic`.
- Put new blocking operations on the right dispatcher or behind an existing
  database/worker seam; never block the Netty event loop.

## Verification

Run the targeted test first, then `./gradlew test` and `./gradlew build`. The
standard test run includes Testcontainers-based database integration tests and
requires an available Docker environment; `e2eTest` is separate. A new Ktor
route must have a test through the installed Application module, not only a
direct function test.
