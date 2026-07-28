---
name: e2e-tests
description: "Create or run full-application Kotest E2E tests. Use when an HTTP, Kafka, or Postgres flow must boot the app with Testcontainers and ./gradlew e2eTest."
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
