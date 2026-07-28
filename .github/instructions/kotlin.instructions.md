---
description: "Applies to Kotlin source and build-script changes in this Ktor backend: naming, types, coroutines, architecture, security, persistence, messaging, and verification."
applyTo: "**/*.kt, **/*.kts"
---

# Kotlin — Nav Ktor backend (`no.nav.budstikka`)

This path-scoped instruction supplies detailed guidance for every `*.kt` and
`*.kts` change. The critical architecture and Kotlin floor remains always-on in
`.github/copilot-instructions.md`.
Use `/kotlin-ktor` for Ktor-specific routes, plugins, authentication,
`StatusPages`, and wiring; use the test skills for their respective test
levels.

## Language and design

1. Keep canonical domain terms Norwegian and all technical or mechanical names
   English.
   - Domain examples: `Brukervarsel`, `Ledervarsel`,
     `Arbeidsgivervarsel`, `DittSykefravaer`, and `Brev`.
   - Technical examples: `save`, `fetch`, `isDead`, and `toColumns`.
   - Renaming a Norwegian term in a published contract is a breaking change.
2. Preserve the hexagonal dependency direction: `domain` depends on nothing,
   `application` depends only on `domain` and application ports, infrastructure
   implements ports, and bootstrap wires the graph.
3. Prefer composition and deep modules over inheritance or shallow
   abstractions. Keep code DRY and SOLID without extracting indirection that
   has no meaningful contract.

## Kotlin idioms

- Prefer Kotlin APIs and idioms; keep Java APIs at interoperability boundaries.
- Use structured concurrency and never `GlobalScope`. Parallelize only
  independent suspend calls.
- Keep visibility as narrow as possible: `private` → `internal` → `public`.
- Prefer immutable data and explicit null handling.
- Use value classes for small domain types when they improve type safety.
- Model closed domains with `sealed interface`, `data object`, and
  `data class`; use exhaustive `when` expressions without `else`.
- Use `fun interface` for ports with one operation.
- Use extensions and scope functions only when they make intent clearer.

## Repository guardrails

- Never put a national identity number, token, or other PII in logs or error
  messages. Never hard-code secrets, URLs, or authentication values.
- Validate authentication configuration, including `issuer` and `audience`,
  through configuration.
- Use the repository's `libs.ktor.*` style for Ktor and `libs.*` for other
  dependencies. `settings.gradle.kts` also exposes the official `ktorLibs`
  catalog; do not introduce it inconsistently or hard-code versions.
- Follow existing Ktor patterns, not Spring patterns.
- Flyway is append-only: add `V<n>__...sql`; never edit a deployed migration.
- Parameterize SQL. Kafka consumption must be idempotent.

## Verification

Run the narrowest relevant test while working. Before reporting completion, run
at least `./gradlew test`; use `./gradlew build` for broad or risky changes.
