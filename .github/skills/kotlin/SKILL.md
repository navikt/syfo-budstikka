---
name: kotlin
description: "Use for idiomatic Kotlin: refactoring, value classes, sealed types, coroutines, nullability, visibility, composition, DRY/SOLID, hexagonal architecture, or /kotlin. Use /kotlin-ktor for Ktor setup."
---

# Kotlin

## Contract

- Keep the code DRY and SOLID without introducing shallow abstractions.
- Follow hexagonal architecture: domain depends on nothing; application depends on domain; infrastructure implements ports.
- Prefer composition over inheritance.
- Prefer Kotlin libraries and idioms. Restrict Java APIs to interoperability boundaries.
- Use structured concurrency. Never use `GlobalScope`. Parallelize only independent suspend calls.
- Keep visibility as narrow as possible: `private` → `internal` → `public`.
- Prefer immutable data and explicit null handling.
- Use value classes for small domain types when they improve type safety.
- Model closed domains with `sealed interface`, `data object`, and `data class`.
- Use exhaustive `when` expressions without `else` for sealed hierarchies.
- Use `fun interface` for ports with a single operation.
- Use extensions and scope functions when they clarify code.
- Keep domain terms Norwegian; technical terms English.

## Checklist

1. Choose the correct layer.
2. Implement idiomatic Kotlin.
3. Verify architecture, naming, and concurrency.
4. Run the relevant Gradle task.
