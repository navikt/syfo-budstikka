---
description: "Used for all Kotlin work in a Nav backend repository: naming, architecture boundaries, security/PII, dependency management, data access and messaging. Portable core; repository facts live in the adapter section at the end."
applyTo: "**/*.kt"
---

# Kotlin — Nav backend

This file is short and binding: hard rules that always apply to `*.kt`. The core is framework-agnostic; anything specific to this repository lives in the [repository adapter](#repository-adapter) at the end. Detailed workflows live in skills.

## Hard rules

1. **Naming:** Norwegian words only for domain words. Technical mechanics in English.
   - The repository glossary owns which domain words stay Norwegian.
   - Ordinary technical names must be English: `save`, `fetch`, `isDead`, `toColumns`.
   - Changing Norwegian words in published contracts is breaking.

2. **Architecture:** respect the repository's established layering. Domain logic stays framework-free; framework and I/O code stays out of the domain layer.

3. **Security and personal data:**
   - No fnr, tokens or other PII in logs or error messages.
   - No hardcoded secrets, URLs or auth values in code.
   - Validate auth config (`issuer`/`audience`) through configuration.

4. **Dependencies and configuration:**
   - Use the repository's version catalogs; do not hardcode versions in build scripts.
   - Follow the repository's established framework patterns — do not import patterns from another framework.

5. **Data access and message flow:**
   - Flyway is append-only: new `V<n>__...sql`, never change a deployed migration.
   - SQL must be parameterised.
   - Kafka consumption must be idempotent.

## Validation

- Run the smallest relevant test command as you go.
- Before reporting done: at least `./gradlew test`.
- For broader or riskier changes: `./gradlew build`.

## Repository adapter

Facts for **this** repository (syfo-budstikka). Replace this section when rolling the core out to another repository.

- Stack: Ktor on Netty (`no.nav.syfo`), hexagonal layering: `domain` is independent, `application` depends on `domain`, `infrastructure` implements ports.
- Domain words that stay Norwegian: `Brukervarsel`, `Ledervarsel`, `Arbeidsgivervarsel`, `DittSykefravaer`, `Brev` (`docs/glossary.md` owns the full vocabulary).
- Version catalogs: `ktorLibs.*` for Ktor, `libs.*` for other dependencies. Use Ktor patterns, not Spring patterns.
- Skills: `/kotlin` for new Kotlin code **and** refactoring, `/kotlin-ktor` for Ktor-specific work (routes/plugins/auth/StatusPages/wiring), `/unit-tests`, `/integration-tests`, `/e2e-tests` for the right test level.
