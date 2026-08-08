# Refactoring candidates

After a green TDD cycle, look for:

- **Duplication** → extract a function or a class
- **Long functions** → break out private helpers (keep the tests on the public interface)
- **Shallow modules** → merge or deepen them, so the interface becomes small and the implementation deep
- **Feature envy** → move the logic to where the data lives (typically: move rules from the route handler into the domain service)
- **Primitive obsession** → introduce value objects (e.g. `Fodselsnummer` instead of `String`, `data class`/`value class`)
- **Existing code** that the new code reveals as problematic

## In a Ktor backend specifically

- Keep route handlers thin: parse/validate the request, delegate to a domain service, translate the result into a response. Business logic belongs in the service, not in `routing { }`.
- Collect configuration and dependency setup in one place (a module function), so `testApplication` can set up an isolated variant.
- Prefer `value class` or `data class` for domain identities over raw `String`/`Long`, so types catch errors at compile time.

**Never refactor while a test is RED.** Get to GREEN first, run `./gradlew test`, and then refactor in small steps with green tests between each one.
