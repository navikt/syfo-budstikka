package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.port.NarmesteLederLookup
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchHeader
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.contract.Tag
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
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

@Tags("E2E")
class ArbeidsgivervarselE2ESpec :
    FunSpec({
        test("AltinnResource is published and delivery reaches SENT") {
            val publisher = FakeArbeidsgiverNotificationPublisher()
            val lookup = FakeNarmesteLederLookup()

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiverNotificationPublisher> { publisher }
                    provide<NarmesteLederLookup> { lookup }
                }.use { app ->
                    val eventId = UUID.randomUUID()
                    app.produceArbeidsgivervarsel(
                        eventId,
                        AltinnResource(AltinnResourceId.DIALOGMOETE),
                    )

                    eventually(30.seconds) {
                        app.deliveryStateFor(eventId) shouldBe sentState()
                        publisher.requests.map { it.eksternId }.shouldContainExactly(eventId.toString())
                        publisher.requests.single().recipient shouldBe
                            ArbeidsgiverNotificationRecipient.AltinnRessurs(AltinnResourceId.DIALOGMOETE)
                    }
                }
        }

        test("NarmesteLeder with an active relation is published and delivery reaches SENT") {
            val publisher = FakeArbeidsgiverNotificationPublisher()
            val lookup = FakeNarmesteLederLookup()
            val leader = PersonIdentifier("00000000000")
            lookup.registerActive(
                TEST_SYKMELDT,
                TEST_ORGNUMMER,
                NarmesteLederRelasjon(leader, emptyList()),
            )

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiverNotificationPublisher> { publisher }
                    provide<NarmesteLederLookup> { lookup }
                }.use { app ->
                    val eventId = UUID.randomUUID()
                    app.produceArbeidsgivervarsel(eventId, NarmesteLeder(TEST_SYKMELDT))

                    eventually(30.seconds) {
                        app.deliveryStateFor(eventId) shouldBe sentState()
                        publisher.requests.map { it.eksternId }.shouldContainExactly(eventId.toString())
                        publisher.requests.single().recipient shouldBe
                            ArbeidsgiverNotificationRecipient.NarmesteLeder(leader, TEST_SYKMELDT)
                    }
                }
        }

        test("NarmesteLeder without an active relation reaches terminal FAILED without publishing") {
            val publisher = FakeArbeidsgiverNotificationPublisher()
            val lookup = FakeNarmesteLederLookup()

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiverNotificationPublisher> { publisher }
                    provide<NarmesteLederLookup> { lookup }
                }.use { app ->
                    val eventId = UUID.randomUUID()
                    app.produceArbeidsgivervarsel(eventId, NarmesteLeder(TEST_SYKMELDT))

                    eventually(30.seconds) {
                        app.deliveryStateFor(eventId) shouldBe failedState()
                        publisher.requests shouldBe emptyList()
                    }
                }
        }
    })

private fun BudstikkaTestApp.produceArbeidsgivervarsel(
    eventId: UUID,
    recipient: no.nav.budstikka.contract.ArbeidsgiverRecipient,
) {
    val dispatch =
        Dispatch(
            reference = "e2e-arbeidsgivervarsel-${UUID.randomUUID()}",
            content =
                ArbeidsgivervarselCreate(
                    orgnummer = TEST_ORGNUMMER,
                    recipient = recipient,
                    tag = Tag.DIALOGMOETE,
                    text = "Du har en ny oppgave",
                    link = "https://nav.no/e2e/arbeidsgivervarsel",
                    sendingWindow = SendingWindow.ONGOING,
                ),
        )

    produce(
        topic = budstikkaTopic,
        key = dispatch.content.partitionKey,
        value = dispatchJson.encodeToString(dispatch),
        headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
    )
}

private fun sentState() =
    ArbeidsgivervarselDeliveryState(
        inboxState = InboxMessageState.PROCESSED.name,
        deliveryState = DeliveryState.SENT.name,
    )

private fun failedState() =
    ArbeidsgivervarselDeliveryState(
        inboxState = InboxMessageState.PROCESSED.name,
        deliveryState = DeliveryState.FAILED.name,
    )

private data class ArbeidsgivervarselDeliveryState(
    val inboxState: String?,
    val deliveryState: String?,
)

private suspend fun BudstikkaTestApp.deliveryStateFor(eventId: UUID): ArbeidsgivervarselDeliveryState =
    database.transact {
        val inboxState =
            InboxMessageTable
                .selectAll()
                .where { InboxMessageTable.eventId eq eventId }
                .singleOrNull()
                ?.get(InboxMessageTable.state)
        val delivery =
            DeliveryTable
                .selectAll()
                .where { DeliveryTable.inboxEventId eq eventId }
                .singleOrNull()

        ArbeidsgivervarselDeliveryState(
            inboxState = inboxState,
            deliveryState = delivery?.get(DeliveryTable.state),
        )
    }
