package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.contract.BrevCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.ExternalNotification
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Varseltype
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional
import java.util.UUID

private val SYKMELDT = PersonIdentifier("11111111111")
private val ORGNUMMER = Orgnummer("999999999")

/**
 * The producing and the consuming side of `budstikka.v1` meet here: a record built exactly the way a
 * Produsent builds it from `:kontrakt` must be accepted by budstikka's own ingest. It proves the two
 * sides share one contract, so neither can drift without a failing test. That both sides also agree
 * on the topic is proven by [no.nav.budstikka.infrastructure.kafka.config.BudstikkaTopicConfigTest].
 */
class ProducerApiIngestTest :
    FunSpec({
        val eventId = EventId(UUID.fromString("00000000-0000-4000-8000-0000000000ff"))

        context("every producer-facing variant is ingested, never dead-lettered") {
            val variants =
                listOf<Pair<String, EncodedDispatch>>(
                    "brukervarselCreate" to
                        Budstikka.brukervarselCreate(
                            eventId = eventId,
                            reference = "ref-1",
                            sykmeldt = SYKMELDT,
                            varseltype = Varseltype.OPPGAVE,
                            text = "Du har en oppgave",
                            externalVarsling = ExternalNotification.smsOnly(smsText = "Sjekk Min side"),
                        ),
                    "brukervarselInactivate" to
                        Budstikka.brukervarselInactivate(eventId = eventId, reference = "ref-1", sykmeldt = SYKMELDT),
                    "dineSykmeldteVarselCreate" to
                        Budstikka.dineSykmeldteVarselCreate(
                            eventId = eventId,
                            reference = "ref-1",
                            sykmeldt = SYKMELDT,
                            orgnummer = ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = "Din ansatte har fått en innkalling",
                        ),
                    "dineSykmeldteVarselInactivate" to
                        Budstikka.dineSykmeldteVarselInactivate(eventId = eventId, reference = "ref-1", sykmeldt = SYKMELDT),
                    "brevCreate" to
                        Budstikka.brevCreate(
                            eventId = eventId,
                            reference = "ref-1",
                            sykmeldt = SYKMELDT,
                            journalpostId = "jp-1",
                        ),
                    "microfrontendEnable" to
                        Budstikka.microfrontendEnable(
                            eventId = eventId,
                            reference = "ref-1",
                            sykmeldt = SYKMELDT,
                            microfrontendId = "syk-dialog",
                        ),
                    "microfrontendDisable" to
                        Budstikka.microfrontendDisable(
                            eventId = eventId,
                            reference = "ref-1",
                            sykmeldt = SYKMELDT,
                            microfrontendId = "syk-dialog",
                        ),
                )
            val expectedTypes =
                mapOf(
                    "brukervarselCreate" to BrukervarselCreate::class,
                    "brukervarselInactivate" to BrukervarselInactivate::class,
                    "dineSykmeldteVarselCreate" to LedervarselCreate::class,
                    "dineSykmeldteVarselInactivate" to LedervarselInactivate::class,
                    "brevCreate" to BrevCreate::class,
                    "microfrontendEnable" to MicrofrontendEnable::class,
                    "microfrontendDisable" to MicrofrontendDisable::class,
                )

            variants.forEach { (name, encoded) ->
                test("$name is hydrated into the inbox") {
                    val inboxRepository = FakeInboxMessageRepository()
                    val deadLetterRepository = FakeDeadLetterRepository()
                    val handler = InboxMessageHandler(inboxRepository, deadLetterRepository)

                    handler.handleBatch(listOf(encoded.toConsumerRecord()))

                    deadLetterRepository.savedDeadLetters.shouldBeEmpty()
                    with(inboxRepository.savedEvents.single()) {
                        this.eventId shouldBe eventId.value
                        reference shouldBe "ref-1"
                        content.partitionKey shouldBe encoded.key
                        content::class shouldBe expectedTypes.getValue(name)
                    }
                }
            }
        }

        test("a redelivered record keeps the eventId budstikka deduplicates on") {
            val inboxRepository = FakeInboxMessageRepository()
            val deadLetterRepository = FakeDeadLetterRepository()
            val handler = InboxMessageHandler(inboxRepository, deadLetterRepository)
            val encoded =
                Budstikka.brevCreate(
                    eventId = eventId,
                    reference = "ref-1",
                    sykmeldt = SYKMELDT,
                    journalpostId = "jp-1",
                )

            handler.handleBatch(listOf(encoded.toConsumerRecord(), encoded.toConsumerRecord(offset = 1L)))

            inboxRepository.savedEvents.map { it.eventId }.distinct() shouldBe listOf(eventId.value)
            deadLetterRepository.savedDeadLetters.shouldBeEmpty()
        }

        test("a Brukervarsel encoded by the library still carries its text into the inbox") {
            val inboxRepository = FakeInboxMessageRepository()
            val handler = InboxMessageHandler(inboxRepository, FakeDeadLetterRepository())
            val encoded =
                Budstikka.brukervarselCreate(
                    eventId = eventId,
                    reference = "ref-1",
                    sykmeldt = SYKMELDT,
                    varseltype = Varseltype.BESKJED,
                    text = "Du har fått et nytt varsel",
                )

            handler.handleBatch(listOf(encoded.toConsumerRecord()))

            inboxRepository.savedEvents
                .single()
                .content
                .shouldBeInstanceOf<BrukervarselCreate>()
                .text shouldBe "Du har fått et nytt varsel"
        }
    })

/** Exactly the conversion a Produsent performs: nothing in the library knows about kafka-clients. */
private fun EncodedDispatch.toConsumerRecord(offset: Long = 0L): ConsumerRecord<String, String?> {
    val recordHeaders = RecordHeaders()
    headerBytes().forEach { (name, bytes) -> recordHeaders.add(name, bytes) }
    return ConsumerRecord(
        topic,
        0,
        offset,
        offset,
        TimestampType.NO_TIMESTAMP_TYPE,
        -1,
        -1,
        key,
        value,
        recordHeaders,
        Optional.empty(),
    )
}
