package no.nav.budstikka.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.Brukervarsel
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import java.util.UUID

class BrukervarselChannelHandlerTest :
    FunSpec({
        test("publishes create payload with delivery reference and returns Sent") {
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
            publisher.published.single() shouldBe PublishedBrukervarsel("brukervarsel-reference", payload)
        }

        test("publishes inactivate payload with delivery reference and returns Sent") {
            val publisher = RecordingMinSideBrukervarselPublisher()
            val handler = BrukervarselChannelHandler(publisher)
            val payload =
                BrukervarselInactivate(
                    reference = "brukervarsel-reference",
                    sykmeldt = PersonIdentifier("12345678901"),
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            publisher.published.shouldHaveSize(1)
            publisher.published.single() shouldBe PublishedBrukervarsel("brukervarsel-reference", payload)
        }

        test("returns Failed without publishing when payload is not a Brukervarsel") {
            val publisher = RecordingMinSideBrukervarselPublisher()
            val handler = BrukervarselChannelHandler(publisher)
            val payload =
                MicrofrontendEnable(
                    personIdentifier = PersonIdentifier("12345678901"),
                    microfrontendId = "syfo-microfrontend",
                )

            val outcome = handler.handle(delivery(payload))

            outcome.shouldBeInstanceOf<DeliveryOutcome.Failed>()
            outcome.reason.shouldContain("MicrofrontendEnable")
            publisher.published.shouldBeEmpty()
        }

        test("wraps transient publisher failures with BRUKERVARSEL context") {
            val handler = BrukervarselChannelHandler(ThrowingMinSideBrukervarselPublisher())
            val payload =
                BrukervarselCreate(
                    personIdentifier = PersonIdentifier("12345678901"),
                    varseltype = Varseltype.BESKJED,
                    text = "Hei",
                )

            val error = shouldThrow<ChannelHandlerFailure> { handler.handle(delivery(payload)) }

            error.message shouldContain "BRUKERVARSEL channel failed"
            error.cause.shouldBeInstanceOf<IllegalStateException>()
            error.stackTrace.any { it.className.contains("BrukervarselChannelHandler") } shouldBe true
        }
    })

private class ThrowingMinSideBrukervarselPublisher : MinSideBrukervarselPublisher {
    override suspend fun publish(
        reference: String,
        brukervarsel: Brukervarsel,
    ) {
        error("downstream unavailable")
    }
}

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = UUID.fromString("00000000-0000-0000-0000-000000000501"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000502"),
        reference = "brukervarsel-reference",
        channel = Channel.BRUKERVARSEL,
        payload = payload,
    )
