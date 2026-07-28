---
name: unit-tests
description: "Create fast Kotest unit or component tests. Use when domain logic, application services with fakes, builders, or focused test data need coverage; choose integration or E2E tests for real adapters or app boot."
---

# Unit tests

## Rules

- Test behaviour through public interfaces, not private functions or implementation details.
- Keep tests fast and local. Avoid application boot, Kafka, and Testcontainers.
- Use Kotest `FunSpec`.
- Use fakes, builders, and focused test data at system boundaries.
- Keep setup small and tests easy to read.
- Split tests when a class requires too much context.

## Checklist

1. Choose the behaviour to prove.
2. Select the test boundary: domain, application service, or adapter with fakes.
3. Write a focused `FunSpec` test.
4. Run the targeted test with `./gradlew test --tests`.
