---
name: integration-tests
description: "Use for Kotest integration tests with real adapters or infrastructure: repository/adapter tests, PostgresTestFixture, Flyway/Exposed, TransactionRunner, or /integration-tests. Use /unit-tests for pure logic. Use /e2e-tests for full application boot."
---

# Integration Tests

## Rules

- Test behavior through public repository, adapter, or application-service interfaces.
- Use real adapters only where fakes would hide the contract.
- Keep the boundary narrow. Avoid full application boot.
- Use Kotest `FunSpec`.
- Use existing fixtures like `PostgresTestFixture`, Flyway, Exposed, and `TransactionRunner`.
- Keep fixture state deterministic: migrate before the spec, reset between tests, close after the spec.
- Do not mark tests as `E2E` unless they boot the whole application.

## Checklist

1. Choose the seam to prove.
2. Select the smallest real boundary.
3. Set up deterministic fixture lifecycle.
4. Write a focused `FunSpec` test.
5. Run the targeted test with `./gradlew test --tests`.
