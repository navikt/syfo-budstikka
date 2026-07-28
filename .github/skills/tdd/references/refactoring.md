# Refactoring checklist

After a green TDD cycle, look for:

- **Duplication** → extract a function or class.
- **Long functions** → extract private helpers while keeping tests on the public interface.
- **Shallow modules** → combine or deepen them so the interface is small and the implementation deep.
- **Feature envy** → move logic to where its data lives (typically move rules
  from a route handler into the domain service).
- **Primitive obsession** → introduce value objects (for example `Fodselsnummer`
  instead of `String`, `data class`/`value class`).
- **Existing code** newly revealed as problematic.

## In a Ktor backend

- Keep route handlers thin: parse and validate the request, delegate to a domain
  service, and translate the result into a response. Business logic belongs in
  the service, not `routing { }`.
- Gather configuration and dependency wiring in one module function so
  `testApplication` can set up an isolated variant.
- Prefer `value class` or `data class` for domain identities over raw
  `String`/`Long`, so types catch errors at compile time.

**Never refactor while a test is RED.** Reach GREEN first, run `./gradlew test`,
then refactor in small steps with green tests between each.
