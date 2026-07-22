package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.RecordingMinSideBrukervarselPublisher
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.dispatch.BrevFallback
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.ExternalVarsling
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.domain.foundation.ReservationLookup
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.FakeDocumentDistributor
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.reservedLookupFor
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Full KRR-brevFallback-spor for #22 (ADR 0009): en reservert bruker (KRR `kanVarsles=false`) med
 * `brevFallback` gir BÅDE et in-app brukervarsel UTEN ekstern varsling OG en BREV-leveranse via
 * dokdist. KRR/dokdist/PDL og brukervarsel-publisher byttes mot fakes; resten er ekte (DB, Kafka,
 * konsument, workers).
 */
@Tags("E2E")
class BrukervarselReservationE2ESpec :
    FunSpec({
        test("reserved user with brevFallback -> in-app brukervarsel without external + BREV via dokdist") {
            val documentDistributor = FakeDocumentDistributor()
            val brukervarselPublisher = RecordingMinSideBrukervarselPublisher()

            BudstikkaTestApp
                .start {
                    provide<DeathLookup> { FakeDeathLookup() }
                    provide<ReservationLookup> { reservedLookupFor(TEST_SYKMELDT) }
                    provide<DocumentDistributor> { documentDistributor }
                    provide<MinSideBrukervarselPublisher> { brukervarselPublisher }
                }.use { app ->
                    val eventId = UUID.randomUUID()
                    val dispatch =
                        Dispatch(
                            reference = "e2e-krr-ref-1",
                            content =
                                BrukervarselCreate(
                                    personIdentifier = TEST_SYKMELDT,
                                    varseltype = Varseltype.OPPGAVE,
                                    text = "Du har en oppgave",
                                    externalVarsling = ExternalVarsling(smsText = "Nytt varsel"),
                                    brevFallback = BrevFallback(journalpostId = "jp-krr-1"),
                                ),
                        )

                    app.produce(
                        topic = app.budstikkaTopic,
                        key = dispatch.content.partitionKey,
                        value = dispatchJson.encodeToString(dispatch),
                        headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
                    )

                    eventually(30.seconds) {
                        app.inboxStateFor(eventId) shouldBe InboxMessageState.PROCESSED.name
                        app.deliveryStatesFor(eventId) shouldBe
                            mapOf(
                                "BRUKERVARSEL" to DeliveryState.SENT.name,
                                "BREV" to DeliveryState.SENT.name,
                            )

                        documentDistributor.requests.map { it.journalpostId }.shouldContainExactly("jp-krr-1")

                        val published = brukervarselPublisher.published.single()
                        (published.brukervarsel as BrukervarselCreate).externalVarsling shouldBe null
                    }
                }
        }
    })

private suspend fun BudstikkaTestApp.inboxStateFor(eventId: UUID): String? =
    database.transact {
        InboxMessageTable
            .selectAll()
            .where { InboxMessageTable.eventId eq eventId }
            .singleOrNull()
            ?.get(InboxMessageTable.state)
    }

private suspend fun BudstikkaTestApp.deliveryStatesFor(eventId: UUID): Map<String, String> =
    database.transact {
        DeliveryTable
            .selectAll()
            .where { DeliveryTable.inboxEventId eq eventId }
            .associate { it[DeliveryTable.channel] to it[DeliveryTable.state] }
    }
