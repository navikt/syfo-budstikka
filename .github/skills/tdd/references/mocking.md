# When to mock

Mock only at **system boundaries**:

- External HTTP services (other domain systems, TokenX/Azure AD token endpoints,
  and integrations).
- Time and randomness (`Clock`, UUID generation).
- Rarely, the filesystem.

Do not mock:

- Your own classes/services (`no.nav.budstikka.*`).
- Internal collaborators.
- The database — prefer real test PostgreSQL through Testcontainers with Flyway
  migrations applied, so tests catch SQL and schema failures.
- Kafka — prefer a real broker through Testcontainers (`KafkaContainer`) over
  mocking a producer or consumer.

Rule of thumb: **if an internal collaborator must be mocked, the module boundary
is probably wrong.**

## Design for testability

At system boundaries, create interfaces that are easy to replace in tests.

**1. Use dependency injection — pass dependencies in**

Pass external dependencies in rather than constructing them internally:

```kotlin
// Easy to replace: HttpClient and Clock are injected
class SoknadService(
    private val pdlClient: PdlClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun registrer(soknad: Soknad): RegistrertSoknad =
        RegistrertSoknad(soknad, mottatt = Instant.now(clock))
}

// Hard to test: constructs the client and reads environment itself
class SoknadService {
    private val pdlClient = PdlClient(System.getenv("PDL_URL"))
}
```

**2. Prefer specific client interfaces to one generic fetcher**

Create one function per external operation rather than one generic function with
conditional logic:

```kotlin
// GOOD: each function is independently mockable / easy to fake
interface PdlClient {
    suspend fun hentPerson(ident: String): Person
    suspend fun hentNavn(ident: String): Navn
}

// BAD: mocking requires conditional logic inside the mock
interface GenericClient {
    suspend fun call(path: String, body: Any?): JsonNode
}
```

A specific interface gives each fake one concrete shape, avoids conditional test
setup, and makes each test's calls obvious.

**3. Prefer a simple fake to a strict mock for behaviour tests**

When testing behaviour rather than interaction, a small handwritten fake of the
interface is often clearer than `mockk` setup: it documents the contract and
does not leak call order into the test.

## Ktor: mock HTTP boundaries, not your own code

For outgoing HTTP, use Ktor's `MockEngine` to fake remote responses rather than
mocking your own client class:

```kotlin
val mockEngine = MockEngine { request ->
    respond(
        content = """{"navn":"Kari"}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
val client = HttpClient(mockEngine) { /* same configuration as production */ }
```

This tests your serialization, error handling, and retry logic through the real
client layer without calling the network.
