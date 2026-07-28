# Test strategy and local execution — syfo-budstikka

How Budstikka is tested end to end and, later, how the whole flow runs locally.
Decisions: B50–B56 in `decisions.md`. This builds on ports and adapters (B28) and
the technology choice (B44).

## Core idea: one shared substrate (B50)

Budstikka is domain-blind and built with ports and adapters. Every external call
(PDL death status, KRR reservation, nearest leader, and the six channels) sits
behind an interface, or port, in `infrastructure`. A local test run therefore
does **not** need a separate build target; it **replaces real adapters with fakes**.

The need for those fakes, and for Kafka plus PostgreSQL, is identical for
automated integration tests and a future interactive local run. Build it **once**
and share it. **Default: keep everything in `src/test`** — fakes, scenario
builders, Testcontainers base, E2E specs, and later an executable local `main()`.
Within one module, `src/test` is already shared across every test class, so no
extra plugin or source set is required.

- **Fakes** (in-memory port implementations), **scenario builders**,
  **Testcontainers base**, **E2E specs**, and later an executable **`main()`** → `src/test`.
- **Never put them in `src/main`.**

Introduce a `testFixtures` source set (the `java-test-fixtures` plugin; the
Gradle name despite Kotlin code) **only if** a concrete sharing need emerges: the
project splits into modules, or an external consumer needs the fakes. As long as
there is one module and only local tests consume fakes, `src/test` is simpler and
provides the same production guarantees. (Bonus in `src/test`: fakes see
`internal` members in `src/main`; `testFixtures` sees only `public`.)

### The production boundary is the guarantee (B50)

The production artifact (fat JAR / Docker image) is built **only** from
`src/main`. Everything in `src/test` is physically absent from the production
JAR. This makes wiring a fake in production **impossible**, enforced by Gradle
and the compiler rather than human discipline.

```
src/main/…                         ← PRODUCTION (only this ships)
  Application.kt                    ← module() (production) + configureApplication(overrides) (wiring seam)
  application.conf                  ← production entry point: refers ApplicationKt.module (REAL adapters)

src/test/…                         ← NOT in production JAR
  fakes/  FakeDeathLookup …                                      ← shared port fakes
  testsupport/  BudstikkaTestApp (harness), KafkaTestContainer   ← shared substrate
  e2e/  DispatchToInboxE2ESpec.kt   ← @Tags("E2E"), boots whole app against Testcontainers + fakes
  LocalApp.kt (main)                ← runnable local run: ./gradlew runLocal
```

**Rejected anti-pattern:** Switch adapters inside `src/main` with
`if (System.getenv("USE_FAKES"))`. That puts fakes in the production JAR, and
one wrong production setting can enable them. The boundary belongs in the build,
not an environment variable.

## Infrastructure: Testcontainers from code (B51)

Start Kafka and PostgreSQL programmatically with Testcontainers — the **same
setup** used by integration tests. Do not use `docker-compose`, which would
create a separate file that drifts from test configuration.

- Database tables (`inbox`/`delivery`) are **fully inspectable while the process
  runs**: the container maps its PostgreSQL port to `localhost`. Log the JDBC URL
  at startup (or pin a host port) and connect psql, DataGrip, or pgweb.
- Each run gets a fresh environment; data does not survive a process restart.
  Live inspection remains available while the run is active.
- Add `withReuse(true)` when a need arises for infrastructure surviving restart
  without compose.

### Shared PostgreSQL container plus schema isolation per fixture (B60)

For speed, all database tests in one JVM share **one** PostgreSQL container:
`PostgresTestFixture` lazily starts a shared container once rather than one new
container per spec, which was the dominant cold-run cost. Isolation remains
because every `PostgresTestFixture` instance uses **its own schema**
(`test_<uuid>`, created at init and dropped in `close()`). `migrate()` runs
Flyway into that schema and the `database` connection sets `currentSchema` to it.
Concurrent specs (Kotest `Concurrent`; see `BudstikkaTestConfiguration`) cannot
touch each other's rows even though `reset()` still `TRUNCATE`s. The full-boot
substrate (`BudstikkaTestApp`/`runLocal`) points the booted app's `database.url`
at the same fixture schema (`?currentSchema=…`), so boot migration, consumer,
and assertions see the same schema. `close()` no longer stops the container;
Testcontainers Ryuk removes it at JVM exit.

### No Texas, tokens, or compose locally

Because fakes (B52) replace every authenticated downstream dependency, local
execution makes no real HTTP calls. It needs **neither Texas/token sidecar, real
tokens, TokenX validation, nor docker-compose**. Real adapter classes remain on
the test runtime classpath, but test/local port registrations replace their
bindings through the `OverridePrevious` wiring seam before use. Local setup is
only Kafka plus PostgreSQL (Testcontainers) plus in-process fakes.

## Fakes: in-process port fakes (B52)

The default is **in-process port fakes**: controllable, in-memory Kotlin
implementations of port interfaces.

```kotlin
class FakeDodsfall : DodsfallOppslag {
    private val dode = mutableSetOf<PersonIdentifier>()
    fun marker(ident: PersonIdentifier) { dode += ident }   // “mark this person dead”
    override suspend fun erDod(ident: PersonIdentifier) = ident in dode
}
```

Benefits: no network, no tokens, fast execution, full control, and they double
as test doubles in unit and integration tests. Port interfaces (B28) are the
seam that makes replacement possible.

Reserve **WireMock/mockserver** for selected client-contract tests where the
goal is to verify an actual HTTP client contract or serialization, not the broad
E2E/local run. Do not choose Ktor MockEngine: it fakes at too low an abstraction
level for a domain-blind router.

## Test levels and scope (B53)

1. **Unit tests** (`domain`, functional core B28/B55): pure, fast, parallel
   decision gates (`DeathGate`, `DecisionProcess`), mapping, and state
   transitions. No containers. (B44/TEKNOLOGI.)
2. **Integration/E2E tests (NOW):** Kotest FunSpec boots the entire app
   (consumer + workers + Ktor) in process against Testcontainers (B51), wires
   port fakes (B52), and asserts the fake channels receive expected delivery.
   It covers inbox → decision → outbox → delivery end to end through shared
   scenario builders (B50). Verify async workers with Kotest `eventually { }`
   against a fake channel or database row once expected state appears. **Opt-in
   (B56):** Full E2E specs are tagged `@Tags("E2E")` and run **only** through
   `./gradlew e2eTest`; default `test`/`check` excludes them so CI/CD does not
   wait for slow container boot on every deployment. Schema/`PostgresTestFixture`
   tests intentionally have **no** E2E tag and run in default `test`.

### Wiring seam (B44/B56)

Production and test share the same boot. `Application.kt` provides a zero-arg
`module()` (referenced by `application.conf`, wiring REAL adapters) that delegates
to `configureApplication(overrides)`. Tests and `LocalApp` boot the app
programmatically and pass fake `provide`s through `overrides` **last**. The seam
is `ktor.di.conflictPolicy = "OverridePrevious"`, set **only** in test
configuration so a later `provide` replaces the real one. Production keeps the
default policy, so duplicate `provide` throws as protection against accidental
override. Fakes never exist in the production JAR (the B50 build boundary).

### Delivered runnable local run — deferred HTTP control plane

`./gradlew runLocal` now boots the **whole app** against Testcontainers
(PostgreSQL plus Kafka) with port fakes wired in (`LocalApp.kt`, using the same
`BudstikkaTestApp` substrate as E2E specs). The process runs until Ctrl+C; JDBC
URL, Kafka bootstrap, formidling topic, and Kafka UI URL are logged at startup
for live inspection. Fakes use the same wiring seam as tests
(`overrides` → `provide`).

Only in the local run, also start **Kafka UI** (`provectuslabs/kafka-ui`) in a
separate Testcontainer so topics, messages, consumer groups, and offsets are
inspectable in a browser while the app runs. Kafka and Kafka UI share a Docker
network: Kafka gets alias `kafka` and internal listener `kafka:19092`; the UI web
port maps to the host. E2E specs disable this (`enableKafkaNetwork = false`, no
UI container) so the gate stays fast without UI overhead. Kafka UI adds about
30–60 seconds to `runLocal` startup (Spring Boot plus health waiting), acceptable
for a local tool.

Still **deferred** until a need appears: an interactive HTTP control plane over
the same run (`POST /dev/formidling`, fake-toggle endpoints, named scenarios)
plus live inspection through pgweb. Build it then as a **thin layer** using the
same fakes and substrate, never in `src/main`.

## Execution

- **Default:** `./gradlew test` (run before completion). Unit plus
  integration/schema tests; full E2E specs (`@Tags("E2E")`) are excluded, so the
  gate stays fast. `./gradlew check` also excludes E2E.
- **Opt-in E2E:** `./gradlew e2eTest` runs **only** E2E-tagged full-boot specs
  against Testcontainers (B56). Use locally or in a separate manual/nightly CI
  job, not the deployment flow.
- **Local run:** `./gradlew runLocal` boots the whole app with Testcontainers and
  fakes. Stop with Ctrl+C, which tears down containers through a shutdown hook.
- Unit tests run in parallel; integration/E2E tests use Testcontainers, so
  Docker must be running.

### Rancher Desktop and Ryuk

On some local Rancher Desktop setups, Ryuk can report its container started
while the mapped localhost port still refuses connections. The symptom affects
Testcontainers on an unchanged `main` as well as feature branches. Confirm that
the container runtime is healthy first, then use this local diagnostic
workaround:

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test
```

This does not change CI and is not the normal repository gate. With Ryuk
disabled, stop leftover test containers manually or restart the local container
runtime after the run.
