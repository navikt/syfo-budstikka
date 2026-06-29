---
description: "Rapids & Rivers i Ktor — River-oppsett, validate/demand/require/interestedIn, publisering og TestRapid. Les kun når repoet allerede bruker no.nav.helse:rapids-rivers."
---

# Rapids & Rivers i Ktor-backend

Rapids & Rivers er Navs eventdrevne rammeverk oppå Kafka. Bruk kun hvis teamet allerede står på Rapids — ikke introduser det i et repo som kjører plain Kafka uten eksplisitt avtale.

`RapidApplication` har sin egen innebygde HTTP-server og livssyklus. Bruker repoet allerede Ktor `EngineMain` for sitt API, må du bevisst velge hvordan de to lever sammen — vanligst er at Rapids-appen eier prosessen og du registrerer evt. ekstra ruter på dens innebygde Ktor-motor. Avklar dette før du blander to motorer.

## Kjernekonsepter

- **Rapid** — det delte Kafka-topicet der hendelser flyter (`<team>.rapid.v1`).
- **River** — en konsument som lytter på spesifikke hendelsestyper.
- **Demand / Require / Reject / Interested in** — validering og filtrering på pakke-nivå.

## Oppsett

```kotlin
import no.nav.helse.rapids_rivers.RapidApplication

fun main() {
    RapidApplication.create(System.getenv()).apply {
        SykmeldingRiver(this, sykmeldingRepository)
        OppfolgingRiver(this, oppfolgingService)
    }.start()
}
```

Nais-envvars Rapids forventer:

```
KAFKA_BROKERS, KAFKA_TRUSTSTORE_PATH, KAFKA_KEYSTORE_PATH, KAFKA_CREDSTORE_PASSWORD
KAFKA_CONSUMER_GROUP_ID=<app-navn>-v1
KAFKA_RAPID_TOPIC=<team>.rapid.v1
```

## En River

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

## Validering — velg riktig predikat

| Predikat | Effekt |
|----------|--------|
| `demandValue(key, value)` | Riveren aktiveres kun hvis feltet har eksakt verdi. Typisk for `@event_name`. |
| `demandKey(key)` | Kun hvis feltet finnes. |
| `requireKey(k1, k2, …)` | Alle felter må finnes, ellers `onError`. |
| `require(key, parser)` | Feltet må finnes og kunne parses. |
| `requireAny(k1, k2)` | Minst ett felt må finnes. |
| `interestedIn(k1, k2)` | Valgfrie felter — fanges hvis tilstede, ingen feil hvis ikke. |
| `rejectKey(key)` / `rejectValue(k, v)` | Skip pakken stille. |

Bruk `demandValue` for event-type-filtrering — det hindrer at Riveren produserer `onError` for hver hendelse som ikke er dens.

## Testing med TestRapid

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

Kjør `./gradlew test` og loggfør resultatet i `.grill/VERIFICATION.md`.

## Feilhåndtering i Rivers

- **Midlertidig feil** (DB nede, nettverk): kast exception → Kafka re-leverer.
- **Permanent feil** (ugyldig payload som passerte validering): log + DLQ-producer + return. Ikke kast — det blokkerer strømmen.
- **Valideringsfeil**: `onError` kalles automatisk. Log med `problems.toExtendedReport()`, ikke republiser.

## Vanlige fallgruver

- **Glemme `demandValue` på `@event_name`** → Riveren trigges for hver melding og spammer `onError`.
- **`requireKey` på valgfritt felt** → meldinger uten feltet feiler unødvendig. Bruk `interestedIn`.
- **Endre `KAFKA_CONSUMER_GROUP_ID`** → reprosessering fra `auto.offset.reset`. Koordiner med drift (Spør først).
- **Store payloads på rapid-topicet** → det er delt mellom mange team. Hold meldinger små, referer tyngre data med ID.
