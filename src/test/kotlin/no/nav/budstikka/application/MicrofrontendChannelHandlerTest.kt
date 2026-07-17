package no.nav.budstikka.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Microfrontend
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import no.nav.budstikka.fakes.microfrontendEnable
import java.util.UUID

class MicrofrontendChannelHandlerTest :
    FunSpec({
        test("publishes the microfrontend payload and returns Sent") {
            val publisher = RecordingMicrofrontendPublisher()
            val handler = MicrofrontendChannelHandler(publisher)
            val payload = microfrontendEnable()

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
                    personIdentifier = TEST_SYKMELDT_2,
                    varseltype = Varseltype.BESKJED,
                    text = "Hei",
                )

            val outcome = handler.handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason.shouldContain("BrukervarselCreate")
            publisher.published.shouldBeEmpty()
        }

        test("wraps transient publisher failures with MICROFRONTEND context") {
            val handler = MicrofrontendChannelHandler(ThrowingMicrofrontendPublisher())
            val payload = microfrontendEnable()

            val error = shouldThrow<ChannelHandlerFailure> { handler.handle(delivery(payload)) }

            error.message shouldContain "MICROFRONTEND channel failed"
            error.cause.shouldBeInstanceOf<IllegalStateException>()
            error.stackTrace.any { it.className.contains("MicrofrontendChannelHandler") } shouldBe true
        }
    })

private class ThrowingMicrofrontendPublisher : MicrofrontendPublisher {
    override suspend fun publish(microfrontend: Microfrontend) {
        error("downstream unavailable")
    }
}

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = UUID.fromString("00000000-0000-0000-0000-000000000401"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000402"),
        reference = "ref-1",
        channel = Channel.MICROFRONTEND,
        payload = payload,
    )
