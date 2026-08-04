package no.nav.budstikka.application

import no.nav.budstikka.application.port.ArbeidsgiverExternalVarsling
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.AltinnResource
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.dispatch.ExternalVarsling

/**
 * Sends only the Altinn-resource path for ARBEIDSGIVERVARSEL. Closing and the Nærmeste leder path
 * are deliberately permanent failures until their dedicated slices exist. Altinn 3 does not allow
 * selecting [ExternalVarsling.channels], and external notifications are always sent as LOEPENDE:
 * [SendingWindowGate] has already waited for budstikka's delivery window.
 */
class ArbeidsgivervarselChannelHandler(
    private val publisher: ArbeidsgiverNotificationPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val create =
            delivery.payload as? ArbeidsgivervarselCreate
                ?: return payloadFailure(delivery)
        val recipient =
            create.recipient as? AltinnResource
                ?: return DeliveryOutcome.Failed(
                    "ARBEIDSGIVERVARSEL NarmesteLeder recipient is not implemented",
                )
        if (create.link.isBlank()) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL link must not be blank")
        }
        val externalVarsling =
            create.externalVarsling?.toPortExternalVarsling()
                ?: if (create.externalVarsling == null) {
                    null
                } else {
                    return DeliveryOutcome.Failed(
                        "ARBEIDSGIVERVARSEL external varsling requires emailTitle, emailText and smsText",
                    )
                }

        return when (
            val response =
                withChannelHandlerFailureContext(Channel.ARBEIDSGIVERVARSEL, "publishing notification") {
                    publisher.publish(
                        ArbeidsgiverNotificationRequest(
                            virksomhetsnummer = create.orgnummer.value,
                            eksternId = (delivery.inboxEventId ?: delivery.id).toString(),
                            grupperingsid = create.sakstilknytning?.sakId,
                            tag = create.tag,
                            tekst = create.text,
                            lenke = create.link,
                            altinnRessurs = recipient.resource,
                            meldingstype = create.meldingstype,
                            externalVarsling = externalVarsling,
                        ),
                    )
                }
        ) {
            ArbeidsgiverNotificationResponse.Published -> DeliveryOutcome.Sent
            is ArbeidsgiverNotificationResponse.Rejected -> DeliveryOutcome.Failed(response.reason)
        }
    }

    private fun payloadFailure(delivery: ClaimedDelivery): DeliveryOutcome =
        when (delivery.payload) {
            is ArbeidsgivervarselInactivate ->
                DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL inactivate is not implemented")
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match ARBEIDSGIVERVARSEL channel: ${delivery.payload::class.simpleName}",
                )
        }

    private fun ExternalVarsling.toPortExternalVarsling(): ArbeidsgiverExternalVarsling? {
        val title = emailTitle ?: return null
        val text = emailText ?: return null
        val sms = smsText ?: return null
        return ArbeidsgiverExternalVarsling(title, text, sms)
    }
}
