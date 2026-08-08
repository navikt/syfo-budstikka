# Good and bad tests

This repository uses **Kotest** (`FunSpec` with `test("...")` blocks and
`shouldBe`-style matchers), not JUnit annotations. See
`src/test/kotlin/no/nav/budstikka/` for the live examples.

## Good tests

**Integration style**: test through real interfaces, not through mocks of internal parts. In this Ktor backend most good tests call the application service or channel handler directly via its public function, or enter through the HTTP layer with `testApplication`/`TestApplication`.

```kotlin
// GOOD: tests observable behavior through the public interface
class BrukervarselChannelHandlerTest :
    FunSpec({
        test("publishes create payload and returns Sent") {
            val publisher = RecordingMinSideBrukervarselPublisher()
            val handler = BrukervarselChannelHandler(publisher)
            val payload =
                BrukervarselCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    varseltype = Varseltype.BESKJED,
                    text = "Hei",
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            publisher.published.shouldHaveSize(1)
        }
    })
```

Characteristics:

- Tests behavior callers care about (outcomes, published payloads, side effects that are visible through the port)
- Uses only the public interface
- Survives internal refactoring
- Describes WHAT, not HOW
- One logical assertion per test

Test names read like a specification. This repository writes them in English —
see `src/test/kotlin/`. Established Norwegian domain nouns (`Brukervarsel`,
`Ledervarsel`, `Brev`, `Varseltype`) and contract field names keep their own
spelling inside the name; they are the domain's terms, not prose, and they must
match what the code calls them.

```kotlin
test("a call without a valid token gives 401") { /* ... */ }
test("a Brukervarsel for a reserved Sykmeldt also produces a Brev delivery") { /* ... */ }
```

## Bad tests

**Implementation-detail tests**: coupled to internal structure.

```kotlin
// BAD: tests that an internal collaborator was called
test("handle calls the publisher") {
    val publisher = mockk<MinSideBrukervarselPublisher>()
    coEvery { publisher.publish(any(), any()) } returns Unit
    BrukervarselChannelHandler(publisher).handle(delivery)
    coVerify { publisher.publish(any(), any()) }   // tests HOW, not WHAT
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
test("saving a delivery writes to the database") {
    repository.saveInTransaction(inboxEventId, listOf(draft))
    val row = dataSource.connection.use {
        it.prepareStatement("SELECT * FROM delivery WHERE reference = ?").run { /* ... */ }
    }
    row.shouldNotBeNull()
}

// GOOD: verifies through the interface
test("a saved delivery can be claimed") {
    repository.saveInTransaction(inboxEventId, listOf(draft))

    val claimed = repository.claim(limit = 10, lease = 5.minutes, maxAttempts = 3, channels = setOf(Channel.BREV))

    claimed.shouldHaveSize(1)
    claimed.single().reference shouldBe draft.reference
}
```

## Tips for this repository

- In Ktor 3.x, `testApplication` does not load modules from `application.conf` automatically — load the module explicitly in the `application { ... }` block, the way `DeadLetterReplayTest` does.
- If you need Postgres/Flyway or Kafka in a test, run against real infrastructure via Testcontainers rather than mocking the database — that gives tests which actually catch SQL and migration errors. Mock HTTP boundaries instead (Entra ID token endpoint, PDL, KRR, the document distributor). See [mocking.md](mocking.md).
