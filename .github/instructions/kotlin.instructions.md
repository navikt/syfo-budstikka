---
description: "Used for all Kotlin work in this Ktor backend repository (no.nav.syfo): routes/plugins, JWT claims, DI, logging, error handling, Gradle Version Catalog, Flyway, Kafka, metrics and tests."
applyTo: "**/*.kt"
---

# Kotlin — Nav Ktor backend (no.nav.syfo)

This file is short and binding: hard rules that always apply to `*.kt`.
Detailed workflows live in skills:

- `/kotlin` for new Kotlin code **and** refactoring (idiomatic Kotlin, types, coroutines, design).
- `/kotlin-ktor` for Ktor-specific work (routes/plugins/auth/StatusPages/wiring).
- `/unit-tests`, `/integration-tests`, `/e2e-tests` for the right test level.

## Hard rules

1. **Naming:** Norwegian words only for domain words. Technical mechanics in English.
   - Domain words that stay Norwegian: `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, `Brev`.
   - Ordinary technical names must be English: `save`, `fetch`, `isDead`, `toColumns`.
   - Changing Norwegian words in published contracts is breaking.

2. **Architecture:** follow the hexagonal model.
   - `domain` is independent.
   - `application` depends on `domain`.
   - `infrastructure` implements ports.

3. **Security and personal data:**
   - No fnr, tokens or other PII in logs or error messages.
   - No hardcoded secrets, URLs or auth values in code.
   - Validate auth config (`issuer`/`audience`) through configuration.

4. **Dependencies and configuration:**
   - Use version catalogs: `ktorLibs.*` for Ktor, `libs.*` for other dependencies.
   - Do not hardcode versions in `build.gradle.kts`.
   - Use the repository's Ktor patterns, not Spring patterns.

5. **Data access and message flow:**
   - Flyway is append-only: new `V<n>__...sql`, never change a deployed migration.
   - SQL must be parameterised.
   - Kafka consumption must be idempotent.

## Validation

- Run the smallest relevant test command as you go.
- Before reporting done: at least `./gradlew test`.
- For broader or riskier changes: `./gradlew build`.
