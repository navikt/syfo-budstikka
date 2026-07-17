package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Oppgavetype
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID

class LedervarselChannelHandlerTest :
    FunSpec({
        test("publishes create payload with delivery reference and returns Sent") {
            val publisher = RecordingLedervarselPublisher()
            val handler = LedervarselChannelHandler(publisher)
            val payload =
                LedervarselCreate(
                    sykmeldt = TEST_SYKMELDT,
                    orgnummer = TEST_ORGNUMMER,
                    oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                    text = "Din ansatte er innkalt",
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            publisher.published.shouldHaveSize(1)
            publisher.published.single() shouldBe PublishedLedervarsel("ledervarsel-reference", payload)
        }

        test("publishes inactivate payload with delivery reference and returns Sent") {
            val publisher = RecordingLedervarselPublisher()
            val handler = LedervarselChannelHandler(publisher)
            val payload =
                LedervarselInactivate(
                    reference = "ledervarsel-reference",
                    sykmeldt = TEST_SYKMELDT,
                )

            val outcome = handler.handle(delivery(payload))

            outcome shouldBe DeliveryOutcome.Sent
            publisher.published.shouldHaveSize(1)
            publisher.published.single() shouldBe PublishedLedervarsel("ledervarsel-reference", payload)
        }

        test("returns Failed without publishing when payload is not a Ledervarsel") {
            val publisher = RecordingLedervarselPublisher()
            val handler = LedervarselChannelHandler(publisher)
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
    })

private fun delivery(payload: DispatchContent): ClaimedDelivery =
    ClaimedDelivery(
        id = UUID.fromString("00000000-0000-0000-0000-000000000601"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000602"),
        reference = "ledervarsel-reference",
        channel = Channel.LEDERVARSEL,
        payload = payload,
    )
