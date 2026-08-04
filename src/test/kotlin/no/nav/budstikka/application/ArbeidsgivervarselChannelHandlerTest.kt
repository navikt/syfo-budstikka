package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.AltinnResource
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgiverMeldingstype
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.dispatch.ExternalVarsling
import no.nav.budstikka.domain.dispatch.NarmesteLeder
import no.nav.budstikka.domain.dispatch.Sakstilknytning
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID

class ArbeidsgivervarselChannelHandlerTest :
    FunSpec({
        test("maps BESKJED with grouping id and stable inbox event id") {
            val publisher = RecordingPublisher()
            val payload = create(sakstilknytning = Sakstilknytning("sak-1"))

            ArbeidsgivervarselChannelHandler(publisher).handle(delivery(payload)) shouldBe DeliveryOutcome.Sent

            publisher.requests.single() shouldBe
                ArbeidsgiverNotificationRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    eksternId = "00000000-0000-0000-0000-000000000702",
                    grupperingsid = "sak-1",
                    tag = Tag.DIALOGMOETE,
                    tekst = "Tekst",
                    lenke = "https://nav.no/lenke",
                    altinnRessurs = AltinnResourceId.DIALOGMOETE,
                    meldingstype = ArbeidsgiverMeldingstype.BESKJED,
                )
        }

        test("maps OPPGAVE, null grouping id and complete external varsling") {
            val publisher = RecordingPublisher()
            val payload =
                create(
                    meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                    externalVarsling = ExternalVarsling(emailTitle = "Tittel", emailText = "Epost", smsText = "SMS"),
                )

            ArbeidsgivervarselChannelHandler(publisher).handle(delivery(payload, inboxEventId = null)) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().grupperingsid shouldBe null
            publisher.requests.single().eksternId shouldBe "00000000-0000-0000-0000-000000000701"
            publisher.requests.single().meldingstype shouldBe ArbeidsgiverMeldingstype.OPPGAVE
            publisher.requests
                .single()
                .externalVarsling
                ?.epostTittel shouldBe "Tittel"
        }

        test("rejects incomplete external varsling permanently") {
            for (externalVarsling in listOf(
                ExternalVarsling(emailText = "text", smsText = "sms"),
                ExternalVarsling(emailTitle = "title", smsText = "sms"),
                ExternalVarsling(emailTitle = "title", emailText = "text"),
            )) {
                val outcome =
                    ArbeidsgivervarselChannelHandler(
                        RecordingPublisher(),
                    ).handle(delivery(create(externalVarsling = externalVarsling)))
                (outcome as DeliveryOutcome.Failed).reason shouldContain "requires"
            }
        }

        test("rejects inactivate and NarmesteLeder permanently") {
            val handler = ArbeidsgivervarselChannelHandler(RecordingPublisher())
            (
                handler.handle(
                    delivery(ArbeidsgivervarselInactivate("reference", TEST_ORGNUMMER)),
                ) as DeliveryOutcome.Failed
            ).reason shouldContain
                "inactivate"
            (handler.handle(delivery(create(recipient = NarmesteLeder(TEST_SYKMELDT)))) as DeliveryOutcome.Failed).reason shouldContain
                "NarmesteLeder"
        }

        test("rejects blank link permanently without publishing") {
            val publisher = RecordingPublisher()

            val outcome =
                ArbeidsgivervarselChannelHandler(publisher).handle(
                    delivery(create().copy(link = "   ")),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldContain "link"
            publisher.requests shouldBe emptyList()
        }
    })

private fun create(
    recipient: no.nav.budstikka.domain.dispatch.ArbeidsgiverRecipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
    meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    sakstilknytning: Sakstilknytning? = null,
    externalVarsling: ExternalVarsling? = null,
) = ArbeidsgivervarselCreate(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient,
    tag = Tag.DIALOGMOETE,
    text = "Tekst",
    link = "https://nav.no/lenke",
    meldingstype = meldingstype,
    sakstilknytning = sakstilknytning,
    externalVarsling = externalVarsling,
)

private fun delivery(
    payload: no.nav.budstikka.domain.dispatch.DispatchContent,
    inboxEventId: UUID? = UUID.fromString("00000000-0000-0000-0000-000000000702"),
) = ClaimedDelivery(
    id = UUID.fromString("00000000-0000-0000-0000-000000000701"),
    inboxEventId = inboxEventId,
    reference = "reference",
    channel = Channel.ARBEIDSGIVERVARSEL,
    payload = payload,
)

private class RecordingPublisher : ArbeidsgiverNotificationPublisher {
    val requests = mutableListOf<ArbeidsgiverNotificationRequest>()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        requests += request
        return ArbeidsgiverNotificationResponse.Published
    }
}
