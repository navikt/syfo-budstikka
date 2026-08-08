# When to mock

Mock only at **system boundaries**:

- External HTTP services (other case-processing systems, TokenX/Entra ID token endpoints, integrations)
- Time and randomness (`Clock`, UUID generation)
- Rarely: the file system

Do not mock:

- Your own classes/services (`no.nav.budstikka.*`)
- Internal collaborators
- The database — prefer a real test Postgres via Testcontainers with the Flyway migrations applied, so the tests catch SQL and schema errors
- Kafka — prefer a real broker via Testcontainers (`KafkaContainer`) over mocking the producer/consumer

Rule of thumb: **if you have to mock an internal collaborator, the module boundary is probably in the wrong place.**

## Design for testability

At system boundaries, build interfaces that are easy to swap out in tests.

**1. Use dependency injection — pass dependencies in**

Pass external dependencies in instead of constructing them internally:

```kotlin
// Easy to swap out: the lookup port and Clock are injected
class DispatchDecider(
    private val reservationLookup: ReservationLookup,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun decide(draft: DeliveryDraft): Decision = /* ... */
}

// Hard to test: constructs the client and reads the environment itself
class DispatchDecider {
    private val krrClient = KrrClient(System.getenv("KRR_URL"))
}
```

**2. Prefer specific client interfaces over one generic fetcher**

Write one function per external operation instead of one generic function with conditional logic:

```kotlin
// GOOD: each port is independently fakeable — see application/port/ and domain/foundation/
fun interface ReservationLookup {
    suspend fun isReserved(ident: PersonIdentifier): Boolean
}

fun interface DeathLookup {
    suspend fun isDead(ident: PersonIdentifier): Boolean
}

// BAD: mocking requires conditional logic inside the mock
interface GenericClient {
    suspend fun call(path: String, body: Any?): JsonNode
}
```

The specific interface gives you: each fake returns one concrete shape, no conditional logic in the test setup, and it is easy to see which calls a test actually triggers.

**3. Prefer a simple fake over a strict mock for behavior tests**

When you are testing behavior (not interaction), a small hand-written fake implementation of the interface is often clearer than `mockk` setup — it documents the contract and does not leak call ordering into the test.

## Ktor: mock HTTP boundaries, not your own code

For outgoing HTTP, use Ktor's `MockEngine` to fake the remote response instead of mocking your own client class:

```kotlin
val mockEngine = MockEngine { request ->
    respond(
        content = """{"navn":"Kari"}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
val client = HttpClient(mockEngine) { /* same config as prod */ }
```

That way you test your own serialization, error handling and retry logic through the real client layer, but without hitting the network.
