package no.nav.budstikka.application.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.DittSykefravaer
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID

class DittSykefravaerChannelHandlerTest :
    FunSpec({
        test("publishes create payload with delivery reference and returns Sent") {
            val publisher = RecordingDittSykefravaerPublisher()
            val payload =
                DittSykefravaerCreate(
                    personIdentifier = TEST_SYKMELDT,
                    text = "Ny melding",
                    messageType = "DIALOGMOTE",
                )

            DittSykefravaerChannelHandler(publisher).handle(delivery(payload)) shouldBe DeliveryOutcome.Sent

            publisher.published shouldHaveSize 1
            publisher.published.single() shouldBe PublishedDittSykefravaer("ditt-sykefravaer-reference", payload)
        }

        test("publishes inactivate payload with delivery reference and returns Sent") {
            val publisher = RecordingDittSykefravaerPublisher()
            val payload = DittSykefravaerInactivate("ditt-sykefravaer-reference", TEST_SYKMELDT)

            DittSykefravaerChannelHandler(publisher).handle(delivery(payload)) shouldBe DeliveryOutcome.Sent

            publisher.published shouldHaveSize 1
            publisher.published.single() shouldBe PublishedDittSykefravaer("ditt-sykefravaer-reference", payload)
        }

        test("returns Failed without publishing when payload is not Ditt Sykefravær") {
            val publisher = RecordingDittSykefravaerPublisher()
            val payload = MicrofrontendEnable(TEST_SYKMELDT, "syfo-microfrontend")

            val outcome = DittSykefravaerChannelHandler(publisher).handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason.shouldContain("MicrofrontendEnable")
            publisher.published.shouldBeEmpty()
        }
    })

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = UUID.fromString("00000000-0000-0000-0000-000000000701"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000702"),
        reference = "ditt-sykefravaer-reference",
        channel = Channel.DITT_SYKEFRAVAER,
        payload = payload,
    )

private data class PublishedDittSykefravaer(
    val reference: String,
    val message: DittSykefravaer,
)

private class RecordingDittSykefravaerPublisher : DittSykefravaerPublisher {
    val published = mutableListOf<PublishedDittSykefravaer>()

    override suspend fun publish(
        reference: String,
        dittSykefravaer: DittSykefravaer,
    ) {
        published += PublishedDittSykefravaer(reference, dittSykefravaer)
    }
}
