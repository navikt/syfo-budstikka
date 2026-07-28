# ADR 0006 — Opt-in gating for full E2E tests (separate Gradle task and Kotest tag)

- Status: Decided (issue #35, local run and E2E harness)
- Date: 2026-07-13
- Related: B50–B53, B56, `docs/teststrategi.md`, ADR 0004, B44
- Note: 0006 reserves 0005 for planned composable-decision-gates ADR (B55).

## Context

`BudstikkaTestApp` boots the full in-process application (Kafka consumer, workers,
Ktor) against Postgres/Kafka Testcontainers through
`configureApplication(overrides)`. It powers automatic E2E specs and
`./gradlew runLocal`. Full boot starts two containers and waits asynchronously,
often tens of seconds. CI/CD must not wait for it on every deploy, while the
lightweight schema-drift test remains in the normal gate.

Environment-variable switching was rejected because it hides executed tests and
makes `./gradlew test` environment-dependent. A separate source-set/module was
rejected because B50 postpones that build complexity while all code is in `src/test`.

## Decision

Use `@Tags("E2E")` plus a separate Gradle task:

1. Tag only full-boot specs. Unit and schema/`PostgresTestFixture` tests remain
   untagged.
2. Default `test` uses `kotest.tags=!E2E`; `./gradlew test`/`check` remains fast
   and deploy never waits for full boot.
3. `./gradlew e2eTest` runs only `kotest.tags=E2E`, reuses `src/test` output with
   separate classpath/task, `shouldRunAfter("test")`, and is not wired into `check`.
   Use it locally and in a manual/nightly CI job.
4. `runLocal` and E2E share `BudstikkaTestApp` as one source of truth.

Only test configuration sets `ktor.di.conflictPolicy = "OverridePrevious"` so a
fake may replace a real adapter; production keeps default duplicate-`provide`
failure, and fakes never enter the production JAR.

## Consequences

Default gate is fast/deterministic while schema drift remains covered. Task names
make execution explicit and no source-set graph is added. Full-boot regressions
can pass until manual/nightly E2E or local larger boot-wiring work, and new specs
must carry `@Tags("E2E")`. Deferred: dedicated nightly/main-merge E2E workflow and
interactive HTTP control plane on the local run.
