package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import java.util.UUID

class MicrofrontendChannelHandlerTest :
    FunSpec({
        test("publishes the microfrontend payload and returns Sent") {
            val publisher = RecordingMicrofrontendPublisher()
            val handler = MicrofrontendChannelHandler(publisher)
            val payload =
                MicrofrontendEnable(
                    personIdentifier = PersonIdentifier("12345678901"),
                    mikrofrontendId = "syfo-mikrofrontend",
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            publisher.published.shouldHaveSize(1)
            publisher.published.single() shouldBe payload
        }

        test("returns Failed without publishing when the payload is not a Microfrontend") {
            val publisher = RecordingMicrofrontendPublisher()
            val handler = MicrofrontendChannelHandler(publisher)
            val payload =
                BrukervarselCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    varseltype = Varseltype.BESKJED,
                    text = "Hei",
                )

            val outcome = handler.handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason.shouldContain("BrukervarselCreate")
            publisher.published.shouldBeEmpty()
        }
    })

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000402"),
        channel = Channel.MICROFRONTEND,
        payload = payload,
    )
