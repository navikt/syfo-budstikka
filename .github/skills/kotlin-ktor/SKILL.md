---
name: kotlin-ktor
description: "Use for Ktor-specific work in no.nav.budstikka: routes, plugins, DI/wiring, logging/MDC, the outgoing HttpClient, and Ktor-related Kafka/Postgres setup — or /kotlin-ktor. Auth mechanisms and token exchange are owned by /auth-overview."
---

# Ktor — NAV-specific (this repository)

Kotlin + Ktor 3.x on Netty, package `no.nav.budstikka` (the Gradle `group` is still `no.nav.syfo`). Java 25, Gradle. Language follows `docs/agents/language-policy.md`: documentation, agent-facing material and technical code identifiers are English, while user-facing product copy is Norwegian Bokmål — as are the established Norwegian domain identifiers (`Brukervarsel`, `Ledervarsel`, `Brev`).

## Skill boundaries

- Use `/kotlin-ktor` when the change touches the Ktor framework (routes, plugins, app wiring).
- Use `/auth-overview` to decide which token a call needs, and for incoming token validation if it is ever added here.
- Use `/integration-tests` or `/e2e-tests` for test-type-specific flows.

## Startup and modules

Production startup is **config-based** with `EngineMain`. Modules are named in `src/main/resources/application.conf` (HOCON) under `ktor.application.modules`, not passed to an `embeddedServer { }` call:

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

The deployed image runs Ktor's `EngineMain` as its main class — `build.gradle.kts` sets `application { mainClass = "io.ktor.server.netty.EngineMain" }` and Jib mirrors it into the container. The repository's own `fun main` lives in `Application.kt`, not `main.kt`, and exists for manual runs: it logs, delegates to `EngineMain.main(args)`, and exits with code 1 on a fatal startup error.

`ApplicationKt.module` is the only entry in the module list, and it delegates straight to `Application.configureApplication()` in the same file. Everything else — plugins, DI, metrics, Flyway migration, dead-letter replay, Kafka consumers, workers, internal routes — is an ordinary call inside `configureApplication`, not a separate Ktor module. Add new startup work there rather than as a second module entry: `src/test/kotlin/no/nav/budstikka/testsupport/BudstikkaTestApp.kt` boots the application by calling `configureApplication(overrides)` directly and `src/test/resources/application-local.conf` sets `application.modules = []`, so anything registered only in `application.conf` never reaches an e2e test or a local run.

## Dependencies (version catalogs)

`build.gradle.kts` resolves **every** dependency, Ktor included, through the `libs` catalog in `gradle/libs.versions.toml` — `libs.ktor.server.core`, `libs.ktor.server.netty`, `libs.ktor.server.call.id`, `libs.ktor.server.di`, `libs.ktor.server.metrics.micrometer`, `libs.ktor.client.core`, `libs.ktor.client.cio`, plus `libs.ktor.client.mock` and `libs.ktor.server.test` on the test classpath. Each of those `[libraries]` entries carries `version.ref = "ktor"`, and the Ktor Gradle plugin (`libs.plugins.ktor`) uses the same ref, so a Ktor upgrade is one edit to `[versions] ktor`.

A second catalog, `ktorLibs`, is declared in `settings.gradle.kts` from the published `io.ktor:ktor-version-catalog`, but nothing in the build references it. That is an open option rather than a rule being broken: adopting it would remove the hand-written Ktor version from `[versions]`, at the cost of pinning Ktor in the `settings.gradle.kts` coordinate instead — the two currently have to be kept in step by hand. Until someone makes that switch deliberately, add a Ktor artifact the way the build already does, as a `[libraries]` entry with `version.ref = "ktor"`, and a third-party library the same way with its own `[versions]` entry.

Check the dependency list before reaching for a Ktor plugin: `ktor-server-auth`, `ktor-server-content-negotiation` and `ktor-client-content-negotiation` are **not** on the classpath. Using one of them is adding a dependency, not calling something that is already there.

## Authentication — outgoing only

This application validates no incoming tokens. There is no `install(Authentication)`, no `authenticate { }` block and no `JWTPrincipal` anywhere in `src/main/kotlin/`; the only routes it serves are the unauthenticated internal ones described below.

`infrastructure/auth/` is the **outgoing** side. `TokenProvider.token(target)` returns a machine-to-machine bearer token for an Entra ID scope (`api://<cluster>.<namespace>.<app>/.default`), and `TexasTokenProvider` obtains it from the NAIS Texas sidecar — endpoint from `NAIS_TOKEN_ENDPOINT`, identity provider defaulting to `entra_id` — caching per target until shortly before expiry. Each client in `infrastructure/client/` calls `tokenProvider.token(config.scope)` and attaches the result with `bearerAuth`. The manifests match: `nais/nais-dev.yaml` and `nais/nais-prod.yaml` set the `texas.nais.io/enabled` annotation and `azure.application.enabled`, and declare `accessPolicy.outbound` only — inbound rules and TokenX are explicitly deferred (see the trailing comment in `nais/nais-dev.yaml`).

Which mechanism a call needs (TokenX on-behalf-of versus Azure AD machine-to-machine) is decided in `/auth-overview`; do not re-derive it here. If inbound authentication is ever added to this application, `/auth-overview` owns the incoming validation setup and `/nais-manifest` owns the matching `accessPolicy.inbound`.

Tokens are secrets: never log one or put it in an exception message. `TexasTokenProvider` reports only the status code when a token request fails.

## Dependency injection

Wiring lives in `bootstrap/DependencyInjection.kt` — read it before adding a dependency. It uses Ktor's own DI (`ktor-server-di`: `dependencies { provide { … } }`, `resolve()`, `by dependencies`), composed from per-area module functions (`databaseModule`, `kafkaModule`, `authModule`, `clientModule`, `gateModule`, `workerModule`, `livenessModule`). Do not pull in a third-party DI framework unprompted.

`installDependencyInjection` runs its `overrides` lambda last, so a test or local run can replace a port with a fake. That only works because the test/local config sets `ktor.di.conflictPolicy = "OverridePrevious"`; production keeps the default policy, where a duplicate registration throws and catches wiring mistakes.

Registrations that own a resource attach `.cleanup { … }` — the Hikari data source, the shared `HttpClient`, the Kafka `MessagePublisher`, the `ConsumerRunner` list and the `BackgroundLoop` list all close through it on shutdown.

## Logging and tracing

```kotlin
install(CallId) {
    retrieve { it.request.headers[NAV_CALL_ID_HEADER] }   // "Nav-Call-Id", see api/Plugins.kt
    generate { UUID.randomUUID().toString() }
    verify { callId: String -> callId.isNotEmpty() }
    header(NAV_CALL_ID_HEADER)
}
```

`CallId` is the only plugin `api/Plugins.kt` installs. Neither `CallLogging` nor `callIdMdc` is installed, and the plugin only covers inbound requests — which here means the internal probes. The Kafka consumers and background workers correlate through SLF4J MDC directly instead: the key names are constants in `application/MdcKeys.kt`, set with `MDC.putCloseable` or a coroutine `MDCContext`, and structured fields are attached with `StructuredArguments.kv`.

`src/main/resources/logback.xml` sends everything to stdout through `LogstashEncoder`, which is the structured JSON that NAIS collects. Never log national identity numbers or special categories of personal data in plain text — use an event id, callId or actor reference instead. Watch throwables as well as messages: a serialization failure echoes the offending body, so the clients and `InboxMessageHandler` deliberately drop the cause rather than let a payload reach a stacktrace.

## HTTP surface

This application is a Kafka consumer/worker. The only routes it serves are `/internal/health/is_alive`, `/internal/health/is_ready` and `/internal/metrics` (`api/InternalApi.kt`), so there is no public HTTP API and no error-response contract, pagination or request validation to follow. If a public API is added, agree its error contract with the consuming team before implementing it — do not assume one already exists.

## Outgoing HttpClient (calls to a downstream service)

One shared `HttpClient(CIO)` is provided in `authModule()` with no configuration block, and injected into `PdlClient`, `KrrClient`, `DocumentDistributionClient` and `TexasTokenProvider` alike. It installs no `HttpTimeout`, no `HttpRequestRetry`, no `Logging` and no `ContentNegotiation` — bodies are read as text and (de)serialized explicitly with kotlinx.serialization. What the clients do carry is the part that matters at the boundary: they turn the downstream status into a domain value so no `HttpResponse` escapes `infrastructure/client/`.

Adding a timeout or retry policy therefore changes the shared client and every downstream with it, including the Texas token call — treat it as a decision, not a formality. See [references/http-client.md](references/http-client.md) for what each client actually does and where the boundaries are (status mapping, the `Nav-Call-Id` header, PII in exception causes; circuit breaking would require Resilience4j — Ktor has no native equivalent).

## Persistence (Postgres / Flyway)

- Flyway migrations in `src/main/resources/database.migration` (`V<n>__<name>.sql`, plus repeatable `R__<name>.sql`), configured via `.locations("classpath:database.migration")` in `DataSource.migrate()` and run from `configureApplication` at startup. Migrations are append-only — never change a migration that has already been deployed.
- Postgres is NAIS-provisioned through `gcp.sqlInstances` in the manifests, with `envVarPrefix: BUDSTIKKA_DB`. `application.conf` reads those injected `BUDSTIKKA_DB_*` variables; do not hardcode a connection string. `toDatabaseConfig()` strips the credentials NAIS embeds in the URL (Hikari wants them separately) and swaps the PEM `sslkey` for the PKCS#8 key from `BUDSTIKKA_DB_SSLKEY_PK8`. `DatabaseConfig.toString()` masks the password on purpose — keep it that way.
- Exposed is the SQL layer (`Database.connect` over the Hikari pool, `TransactionRunner` for transactional units of work).
- Review schema and storage choices for personal data with
  `/architecture-review`. When the choice passes the ADR gate, recommend the
  documented route and wait for the user's choice before `/domain-modeling`
  records it.

## Kafka (event consumer/producer)

- Semantics are **at-least-once**, not exactly-once: `ConsumerRunner` calls `commitSync` only after the handler has processed a whole poll batch, so a throwing handler leaves the offset uncommitted and the batch is re-polled after a backoff. A record that can never be handled halts its partition (visible as consumer lag) rather than being skipped.
- Deduplication is on the **event id header** (`DispatchHeader.EVENT_ID`), not on Kafka key or offset: `InboxMessageHandler` persists a hydrated inbox row keyed by that id. Keep new consumers idempotent on the same footing.
- Unparseable input is dead-lettered rather than retried — `DeadLetterMessageRepository` stores the record with a reason code from the `DeadLetter` hierarchy, and `DeadLetterReplayer` replays the table when `deadLetterReplay.enabled` is set. Confirm topic access and `accessPolicy` in NAIS via `/nais-manifest`.
- Log with event id plus topic/partition/offset, never a raw payload. A dead letter may have no event id at all, which is why `InboxMessageHandler` correlates it by Kafka coordinates and logs neither the payload nor the parse throwable.

## Graceful shutdown

`EngineMain` starts an `EmbeddedServer`, whose `start` installs a JVM shutdown hook, so `SIGTERM` is handled without any code here. Draining the parts that matter for this application — the Kafka consumers and the background workers — happens through the DI `cleanup` blocks: `ConsumerRunner.close()` wakes the consumer and joins its coroutine, `BackgroundLoop.close()` cancels the scope and joins, each with a 5-second timeout that logs a warning when it elapses. You do not need manual readiness toggling; flipping `readiness=false` by hand is an anti-pattern. Pod lifecycle on the platform side (`preStop`, `terminationGracePeriodSeconds`) belongs to `/nais-manifest`.

## Verification

Quality gates are deterministic: `./gradlew test` and `./gradlew build` (`check` also depends on `ktlintCheck`). Ktor startup and routes are tested with `testApplication { }` / `TestApplication { }` from `libs.ktor.server.test` (`io.ktor:ktor-server-test-host`) — see `src/test/kotlin/no/nav/budstikka/bootstrap/DeadLetterReplayTest.kt`, which drives a module in isolation with a `MapApplicationConfig` and DI overrides. Full-boot specs are tagged `E2E` and excluded from `./gradlew test`; `/e2e-tests` owns those. No "looks right" claim without fresh command output + exit code.
