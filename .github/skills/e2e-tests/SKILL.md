---
name: e2e-tests
description: "Use for full app-boot Kotest E2E tests: HTTP/Kafka/Postgres flows, Testcontainers, @Tags(\"E2E\"), TestContext, ./gradlew e2eTest, or /e2e-tests. Use /integration-tests for real adapters without app boot."
---

# E2E Tests

## Rules

- Test one full user or system flow through the running app.
- Assert observable effects, not internal implementation.
- Use Testcontainers for external boundaries like Kafka and Postgres.
- Use Kotest `FunSpec`.
- Mark full app-boot specs with `@Tags("E2E")`.
- Use `TestContext` when setup would otherwise hide the flow.
- Run with `./gradlew e2eTest`; default `./gradlew test` excludes `E2E`.

## Checklist

1. Choose the full flow to prove.
2. Boot the app with required Testcontainers.
3. Write a focused `FunSpec` with `@Tags("E2E")`.
4. Run `./gradlew e2eTest`.
