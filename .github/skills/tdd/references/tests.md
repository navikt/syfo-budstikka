# Good and bad test examples

## Good tests

**Integration style:** test through real interfaces, not mocks of internal parts.
In this Ktor backend, most good tests enter through HTTP with `testApplication`,
or call a domain service through its public function.

```kotlin
// GOOD: tests observable behaviour through the HTTP interface
@Test
fun `root-endepunkt svarer 200`() = testApplication {
    application { configureRouting() }
    assertEquals(HttpStatusCode.OK, client.get("/").status)
}
```

Characteristics:

- Test behaviour callers care about (status codes, response content, and side
  effects visible through the API).
- Use only a public interface.
- Survive internal refactoring.
- Describe **what**, not **how**.
- Keep one logical assertion per test.

Norwegian test names in backticks read as specifications:

```kotlin
@Test fun `kall uten gyldig token gir 401`() = testApplication { /* ... */ }
@Test fun `søknad med ugyldig fnr avvises med 400`() = testApplication { /* ... */ }
```

## Bad tests

**Implementation-detail tests** couple to internal structure.

```kotlin
// BAD: tests that an internal collaborator was called
@Test
fun `behandleSoknad kaller validator`() {
    val validator = mockk<SoknadValidator>()
    every { validator.valider(any()) } returns true
    behandleSoknad(soknad, validator)
    verify { validator.valider(soknad) }   // tests HOW, not WHAT
}
```

Red flags:

- Mocking internal collaborators.
- Testing private functions.
- Asserting call count or order (`verify(exactly = ...)` on internal code).
- A test breaks on refactoring without behaviour change.
- The test name describes **how**, not **what**.

```kotlin
// BAD: bypasses the interface to verify
@Test
fun `lagreSoknad skriver til databasen`() = runBlocking {
    lagreSoknad(Soknad(fnr = "..."))
    val rad = dataSource.connection.use {
        it.prepareStatement("SELECT * FROM soknad WHERE fnr = ?").run { /* ... */ }
    }
    assertNotNull(rad)
}

// GOOD: verifies through the interface
@Test
fun `lagret søknad kan hentes igjen`() = runBlocking {
    val lagret = soknadService.lagre(Soknad(fnr = "00000000000"))
    val hentet = soknadService.hent(lagret.id)
    assertEquals("00000000000", hentet.fnr)
}
```

## Repository-specific guidance

- In Ktor 3.x, install `Application.module()` or `configureApplication { ... }`
  explicitly in Ktor tests so bindings are visible and do not implicitly depend
  on `application.conf`.
- When a test needs PostgreSQL/Flyway or Kafka, use real infrastructure through
  Testcontainers instead of mocking the database. This catches SQL and migration
  failures; mock HTTP boundaries (TokenX/Azure AD and other domain systems)
  instead. Read [mocking.md](mocking.md).
