# Good and bad tests

## Good tests

**Integration style**: test through real interfaces, not through mocks of internal parts. In this Ktor backend most good tests enter via the HTTP layer with `testApplication`, or call the domain service directly via its public function.

```kotlin
// GOOD: tests observable behavior through the HTTP interface
@Test
fun `root endpoint responds 200`() = testApplication {
    application { configureRouting() }
    assertEquals(HttpStatusCode.OK, client.get("/").status)
}
```

Characteristics:

- Tests behavior callers care about (status codes, response content, side effects that are visible through the API)
- Uses only the public interface
- Survives internal refactoring
- Describes WHAT, not HOW
- One logical assertion per test

Test names in backticks read like a specification. This repository writes them in
English — see `src/test/kotlin/`. Norwegian domain nouns (`søknad`, `sykmelding`,
`brukervarsel`) and field names (`fnr`) keep their own spelling inside the name;
they are the domain's terms, not prose, and they must match what the code calls them.

```kotlin
@Test fun `a call without a valid token gives 401`() = testApplication { /* ... */ }
@Test fun `a søknad with an invalid fnr is rejected with 400`() = testApplication { /* ... */ }
```

## Bad tests

**Implementation-detail tests**: coupled to internal structure.

```kotlin
// BAD: tests that an internal collaborator was called
@Test
fun `behandleSoknad calls the validator`() {
    val validator = mockk<SoknadValidator>()
    every { validator.valider(any()) } returns true
    behandleSoknad(soknad, validator)
    verify { validator.valider(soknad) }   // tests HOW, not WHAT
}
```

Red flags:

- Mocking internal collaborators
- Testing private functions
- Asserting on call count or ordering (`verify(exactly = ...)` on internal code)
- The test breaks on a refactoring with no behavior change
- The test name describes HOW, not WHAT

```kotlin
// BAD: goes around the interface to verify
@Test
fun `lagreSoknad writes to the database`() = runBlocking {
    lagreSoknad(Soknad(fnr = "..."))
    val rad = dataSource.connection.use {
        it.prepareStatement("SELECT * FROM soknad WHERE fnr = ?").run { /* ... */ }
    }
    assertNotNull(rad)
}

// GOOD: verifies through the interface
@Test
fun `a stored søknad can be read back`() = runBlocking {
    val lagret = soknadService.lagre(Soknad(fnr = "12345678901"))
    val hentet = soknadService.hent(lagret.id)
    assertEquals("12345678901", hentet.fnr)
}
```

## Tips for this repository

- In Ktor 3.x, `testApplication` does not load modules from `application.yaml` automatically — load the module explicitly in the `application { ... }` block, the way `ServerTest` does.
- If you need Postgres/Flyway or Kafka in a test, run against real infrastructure via Testcontainers rather than mocking the database — that gives tests which actually catch SQL and migration errors. Mock HTTP boundaries instead (TokenX/Azure AD, other fagsystemer). See [mocking.md](mocking.md).
