# Technology choices — syfo-budstikka

Use idiomatic, modern Kotlin: **not** Spring-like. Testability is a first-class
requirement: the domain layer is framework-free and is tested purely and quickly. Put
all dependencies in the version catalog (`gradle/libs.versions.toml`, referenced as
`libs.*` / `ktorLibs.*`), never as hard-coded versions.

## Runtime and framework

- **Kotlin/JVM** on **Ktor + Netty** (`io.ktor.server.netty.EngineMain`), already in the skeleton.
- **DI: Ktor built-in dependency injection** (Ktor 3.2+), not Koin or Spring.
  Use constructor injection and wire in `Application.*Module()`. Keep `domain`
  framework-free.

## Coding

- Use `kotlin.time.Duration` for intervals, not `java.time.Duration`. The only
  exception is where the Kafka API requires it (`Consumer.poll()`).

## Data

- **Postgres 18** (esyfovarsel ran 17), Cloud SQL through NAIS.
- **Exposed DSL** (JetBrains): typed SQL DSL, *not* DAO/ORM magic and *not* raw JDBC.
  The DSL provides parameterised queries. It must express `FOR UPDATE SKIP LOCKED`
  for worker row locking (B15/B27). Use **HikariCP** as the connection pool.
- **Flyway** for schema changes (`src/main/resources/database.migration/V<n>__*.sql`), additively.
- **UUID v7** (time-sorted) for internal IDs such as `delivery.id` (B16). It provides
  better B-tree locality than v4 and supports age-based retention `DELETE` (B42).
  **Postgres 18 includes `uuidv7()`**: generate IDs in the database with
  `DEFAULT uuidv7()` (set in the Flyway migration), with no application-side generator.
  **Budstikka standard: `java.util.UUID` through `javaUUID("id")`** (package
  `org.jetbrains.exposed.v1.core.java`): no experimental opt-in and best interoperability
  with Kafka/JDBC/serialization on a pure JVM backend. (`uuid("id")` in Exposed 1.0 is
  `kotlin.uuid.Uuid` and requires `@OptIn(ExperimentalUuidApi::class)`, so do not use it.)
  Mark the column `.databaseGenerated()` so Exposed reads back the ID instead of sending
  one. Do **not** use `.autoGenerate()`: it creates a client-side v4. `eventId` (B4) is
  set by producer apps (the incoming Kafka contract), not by budstikka’s database.

## Kafka

- Use **plain Apache Kafka** (`kafka-clients`), with consumer and producer in their own
  coroutine alongside the Ktor server (see `/kafka-topic`). Do not use Rapids & Rivers.
  NAIS injects SSL configuration.

## Project structure (DDD / ports and adapters)

Packages under `no.nav.budstikka`:

- **`domain`**: pure core, containing the model (`Dispatch`, `Decision`, states) and
  pure `apply` functions for composable decision gates (B28/B55). No I/O or framework.
  Fast, parallelisable unit tests.
- **`application`**: use-case orchestrators, including background workers
  (`InboxMessageWorker`, `DeliveryWorker`) and other drivers that coordinate `domain`
  with application ports. It speaks only domain and ports, never transport types. It may
  depend on `domain` and application ports; nothing inward points to it.
- **`infrastructure`**: imperative shell and adapters: Kafka, Exposed repositories,
  external clients (KRR, PDL, nærmeste leder, dokdist, notification producer API),
  DataSource, and configuration. Worker *mechanics* (`BackgroundLoop`, `Heartbeat`) live
  here: lifecycle and plumbing without domain knowledge.
- **`api`**: Ktor routes, including internal endpoints
  (`/internal/health/is_alive`, `/internal/health/is_ready`, and
  `/internal/metrics`) and, if needed, admin.

Placement test (adapter versus use case): a class named after a transport type
(`ConsumerRecord`, `ApplicationCall`, HTTP request) is a driving adapter and belongs in
`infrastructure` (or `api` for HTTP). A class speaking only domain and ports is a use case
and belongs in `application`. Pure lifecycle and plumbing belong in `infrastructure`.
`InboxMessageHandler` takes `ConsumerRecord` and reads Kafka headers, so it is an adapter
in `infrastructure/kafka`; `InboxMessageWorker` takes only repository and configuration,
so it is a use case in `application`. Introduce a port only when there is a reason (two or
more drivers, domain logic that must be tested without transport, or complex orchestration),
not speculatively. DI wiring that touches `application` belongs in `bootstrap` (the
composition root), never `infrastructure`. See ADR 0003.

Mapping to B28: `domain` = functional core; `application` and `infrastructure` =
imperative shell; `api` = HTTP edge.

## Test

- **Kotest** with the **FunSpec** style as test framework.
- **MockK** for mocking.
- **Testcontainers** through the Kotest extension for Postgres/Kafka integration tests.
- Two levels: (1) fast, pure **unit tests** of `domain` (decision gates, mapping), with no
  containers and run **in parallel**; (2) **integration tests** (repositories, consumer,
  end-to-end) with Testcontainers. Configure parallelism in Kotest.
- **ktlint** (already in the repository) enforces code style. Run `./gradlew test` before
  declaring work complete.
- For the local test/e2e strategy (shared substrate in `src/test`, production boundary via
  build, port fakes, deferred interactive run), see `teststrategi.md` (B50–B53).
