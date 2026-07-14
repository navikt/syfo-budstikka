---
name: unit-tests
description: "Use for fast Kotest unit/component tests: domain logic, application services with fakes, builders, focused test data, or /unit-tests. Use /integration-tests for real adapters. Use /e2e-tests for app boot."
---

# Unit Tests

## Rules

- Test behavior through public interfaces, not private functions or implementation details.
- Keep tests fast and local. Avoid application boot, Kafka, and Testcontainers.
- Use Kotest `FunSpec`.
- Use fakes, builders, and focused test data at system boundaries.
- Keep setup small and tests easy to read.
- Split tests when a class requires too much context.

## Checklist

1. Choose the behavior to prove.
2. Select the test boundary: domain, application service, or adapter with fakes.
3. Write a focused `FunSpec` test.
4. Run the targeted test with `./gradlew test --tests`.