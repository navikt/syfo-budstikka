---
description: "Provides Rapids & Rivers patterns for Ktor: River setup, validate/demand/require/interestedIn, publishing, and TestRapid. Read only when the repository already uses no.nav.helse:rapids-rivers."
---

# Rapids & Rivers in a Ktor backend

Rapids & Rivers is Nav’s event-driven framework on top of Kafka. Use it only if
the team already uses Rapids; do not introduce it into a plain Kafka repository
without explicit agreement.

`RapidApplication` has its own embedded HTTP server and lifecycle. If the
repository already uses Ktor `EngineMain` for its API, deliberately decide how
the two coexist. Most often the Rapids application owns the process and optional
extra routes are registered on its embedded Ktor engine. Clarify this before
combining two engines.

## Core concepts

- **Rapid**: the shared Kafka topic where events flow (`<team>.rapid.v1`).
- **River**: a consumer that listens for specific event types.
- **Demand / Require / Reject / Interested in**: packet-level validation and filtering.

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

NAIS environment variables expected by Rapids:

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
        logger.error("Validation error: ${problems.toExtendedReport()}")
    }
}
```

## Validation: select the right predicate

| Predicate | Effect |
|----------|--------|
| `demandValue(key, value)` | Activates the River only when a field has the exact value. Typical for `@event_name`. |
| `demandKey(key)` | Activates only if the field exists. |
| `requireKey(k1, k2, …)` | Every field must exist, otherwise `onError`. |
| `require(key, parser)` | The field must exist and be parseable. |
| `requireAny(k1, k2)` | At least one field must exist. |
| `interestedIn(k1, k2)` | Optional fields: capture when present, no error when absent. |
| `rejectKey(key)` / `rejectValue(k, v)` | Silently skip the packet. |

Use `demandValue` for event-type filtering: it prevents a River from producing
`onError` for every event that is not its own.

## Testing with TestRapid

```kotlin
class SykmeldingRiverTest {
    private val testRapid = TestRapid()
    private val repo = InMemorySykmeldingRepository()

    init { SykmeldingRiver(testRapid, repo) }

    @Test
    fun `processes sykmelding_sendt`() {
        testRapid.sendTestMessage("""
            {
              "@event_name": "sykmelding_sendt",
              "@id": "550e8400-e29b-41d4-a716-446655440000",
              "@created_at": "2026-01-01T08:00:00",
              "sykmeldingId": "s1",
              "fnr": "00000000000",
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

Run `./gradlew test` and return result and exit code in KOKK_RESULT/PR.

## Error handling in Rivers

- **Transient failure** (database down, network): throw an exception and Kafka redelivers.
- **Permanent failure** (invalid payload that passed validation): log, send to DLQ,
  and return. Do not throw: that stalls the stream.
- **Validation failure**: `onError` runs automatically. Log with
  `problems.toExtendedReport()` and do not republish.

## Common pitfalls

- **Forgetting `demandValue` on `@event_name`** makes the River trigger for every
  message and spam `onError`.
- **Using `requireKey` for an optional field** makes messages without it fail
  unnecessarily. Use `interestedIn`.
- **Changing `KAFKA_CONSUMER_GROUP_ID`** triggers reprocessing from
  `auto.offset.reset`. Coordinate with operations; ask first.
- **Large payloads on the rapid topic** affect many teams. Keep messages small
  and reference heavier data by ID.
