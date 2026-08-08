---
description: "Rapids & Rivers in Ktor — River setup, validate/demand/require/interestedIn, publishing and TestRapid. Read only when the repository already uses no.nav.helse:rapids-rivers."
---

# Rapids & Rivers in a Ktor backend

Rapids & Rivers is Nav's event-driven framework on top of Kafka. Use it only if the team is already on Rapids — do not introduce it into a repository running plain Kafka without an explicit agreement.

`RapidApplication` has its own built-in HTTP server and lifecycle. If the repository already uses Ktor `EngineMain` for its API, you must deliberately decide how the two live together — most commonly the Rapids app owns the process and you register any extra routes on its built-in Ktor engine. Settle this before mixing two engines.

## Core concepts

- **Rapid** — the shared Kafka topic where events flow (`<team>.rapid.v1`).
- **River** — a consumer that listens for specific event types.
- **Demand / Require / Reject / Interested in** — validation and filtering at packet level.

## Setup

```kotlin
import no.nav.helse.rapids_rivers.RapidApplication

fun main() {
    RapidApplication.create(System.getenv()).apply {
        SykmeldingRiver(this, sykmeldingRepository)
        OppfolgingRiver(this, oppfolgingService)
    }.start()
}
```

Nais env vars that Rapids expects:

```
KAFKA_BROKERS, KAFKA_TRUSTSTORE_PATH, KAFKA_KEYSTORE_PATH, KAFKA_CREDSTORE_PASSWORD
KAFKA_CONSUMER_GROUP_ID=<app-name>-v1
KAFKA_RAPID_TOPIC=<team>.rapid.v1
```

## A River

```kotlin
class SykmeldingRiver(
    rapidsConnection: RapidsConnection,
    private val repository: SykmeldingRepository,
) : River.PacketListener {

    init {
        River(rapidsConnection).apply {
            validate { it.demandValue("@event_name", "sykmelding_sendt") }
            validate { it.requireKey("sykmeldingId", "fnr", "fom", "tom") }
            validate { it.require("@created_at", JsonNode::asLocalDateTime) }
            validate { it.interestedIn("grad") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        val eventId = packet["@id"].asText()
        if (repository.alleredeProsessert(eventId)) return

        repository.lagre(
            sykmeldingId = packet["sykmeldingId"].asText(),
            fnr = packet["fnr"].asText(),
            fom = packet["fom"].asLocalDate(),
            tom = packet["tom"].asLocalDate(),
            grad = packet["grad"].takeIf { !it.isMissingNode }?.asInt(),
        )
        repository.markerProsessert(eventId)

        context.publish(
            JsonMessage.newMessage(
                mapOf(
                    "@event_name" to "oppfolging_opprettet",
                    "@id" to UUID.randomUUID().toString(),
                    "@created_at" to LocalDateTime.now(),
                    "@produced_by" to "syfo-budstikka",
                    "sykmeldingId" to packet["sykmeldingId"].asText(),
                ),
            ).toJson(),
        )
    }

    override fun onError(problems: MessageProblems, context: MessageContext) {
        logger.error("Valideringsfeil: ${problems.toExtendedReport()}")
    }
}
```

## Validation — choose the right predicate

| Predicate | Effect |
|-----------|--------|
| `demandValue(key, value)` | The River is activated only if the field has exactly this value. Typical for `@event_name`. |
| `demandKey(key)` | Only if the field is present. |
| `requireKey(k1, k2, …)` | All fields must be present, otherwise `onError`. |
| `require(key, parser)` | The field must be present and parseable. |
| `requireAny(k1, k2)` | At least one field must be present. |
| `interestedIn(k1, k2)` | Optional fields — captured if present, no error if not. |
| `rejectKey(key)` / `rejectValue(k, v)` | Skip the packet silently. |

Use `demandValue` for event-type filtering — it prevents the River from producing `onError` for every event that is not its own.

## Testing with TestRapid

```kotlin
class SykmeldingRiverTest {
    private val testRapid = TestRapid()
    private val repo = InMemorySykmeldingRepository()

    init { SykmeldingRiver(testRapid, repo) }

    @Test
    fun `prosesserer sykmelding_sendt`() {
        testRapid.sendTestMessage("""
            {
              "@event_name": "sykmelding_sendt",
              "@id": "550e8400-e29b-41d4-a716-446655440000",
              "@created_at": "2026-01-01T08:00:00",
              "sykmeldingId": "s1",
              "fnr": "<SYNTHETIC_FNR>",
              "fom": "2026-01-01",
              "tom": "2026-03-31"
            }
        """)

        assertEquals("s1", repo.finn("s1")?.sykmeldingId)
        val publisert = testRapid.inspektør.message(0)
        assertEquals("oppfolging_opprettet", publisert["@event_name"].asText())
    }
}
```

Run `./gradlew test` and return the result to the active task.

## Error handling in Rivers

- **Temporary error** (DB down, network): throw an exception → Kafka redelivers.
- **Permanent error** (invalid payload that passed validation): log + DLQ producer + return. Do not throw — that blocks the stream.
- **Validation error**: `onError` is called automatically. Log with `problems.toExtendedReport()`, do not republish.

## Common pitfalls

- **Forgetting `demandValue` on `@event_name`** → the River is triggered for every message and spams `onError`.
- **`requireKey` on an optional field** → messages without the field fail unnecessarily. Use `interestedIn`.
- **Changing `KAFKA_CONSUMER_GROUP_ID`** → reprocessing from `auto.offset.reset`. Coordinate with operations (Ask first).
- **Large payloads on the rapid topic** → it is shared between many teams. Keep messages small, reference heavier data by ID.
