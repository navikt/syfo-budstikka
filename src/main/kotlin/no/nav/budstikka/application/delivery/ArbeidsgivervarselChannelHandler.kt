package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Sends ARBEIDSGIVERVARSEL through an [AltinnResource] or a Nærmeste leder path. The latter resolves
 * the active leader at send time, so a deferred delivery follows leader changes without persisting
 * the leader's identifier. External notifications are always sent as LOEPENDE: [SendingWindowGate]
 * has already waited for budstikka's delivery window. A requested external notification without a
 * leader email fails the entire delivery terminally; it does not degrade to in-app only. Closing
 * remains a permanent failure.
 */
class ArbeidsgivervarselChannelHandler(
    private val publisher: ArbeidsgiverNotificationPublisher,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DeliveryMetrics,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val create =
            delivery.payload as? ArbeidsgivervarselCreate
                ?: return payloadFailure(delivery)
        if (create.link.isBlank()) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL link must not be blank")
        }
        if (create.tag.isBlank()) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL tag must not be blank")
        }
        val notificationRecipient =
            when (val recipient = create.recipient) {
                is AltinnResource ->
                    if (recipient.resource.isBlank()) {
                        return DeliveryOutcome.Failed(
                            "ARBEIDSGIVERVARSEL recipient.resource must not be blank",
                        )
                    } else {
                        recipient.toNotificationRecipient()
                    }
                is NarmesteLederRecipient -> {
                    val relation =
                        withChannelHandlerFailureContext(
                            Channel.ARBEIDSGIVERVARSEL,
                            "resolving nærmeste leder",
                        ) {
                            narmesteLederLookup.findActive(recipient.sykmeldt, create.orgnummer)
                        }
                            ?: return narmesteLederFailure(NarmesteLederMissingReason.MISSING_ACTIVE_LEADER)
                    if (recipient.externalVarsling != null && relation.epostadresser.isEmpty()) {
                        return narmesteLederFailure(NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS)
                    }
                    ArbeidsgiverNotificationRecipient.NarmesteLeder(
                        narmesteLederFnr = relation.narmesteLederFnr,
                        ansattFnr = recipient.sykmeldt,
                        externalVarsling =
                            recipient.externalVarsling?.let {
                                NarmesteLederExternalVarsling(
                                    it.emailTitle,
                                    it.emailText,
                                    relation.epostadresser,
                                )
                            },
                    )
                }
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
                            recipient = notificationRecipient,
                            meldingstype = create.meldingstype,
                            visibleUntil = create.visibleUntil,
                        ),
                    )
                }
        ) {
            ArbeidsgiverNotificationResponse.Published -> DeliveryOutcome.Sent
            is ArbeidsgiverNotificationResponse.Rejected -> DeliveryOutcome.Failed(response.reason)
        }
    }

    private fun AltinnResource.toNotificationRecipient() =
        ArbeidsgiverNotificationRecipient.AltinnRessurs(
            resource = resource,
            externalVarsling =
                externalVarsling?.let {
                    AltinnExternalVarsling(it.emailTitle, it.emailText, it.smsText)
                },
        )

    private fun narmesteLederFailure(reason: NarmesteLederMissingReason): DeliveryOutcome.Failed {
        metrics.narmesteLederMissing(reason)
        return DeliveryOutcome.Failed(
            "ARBEIDSGIVERVARSEL NarmesteLeder delivery unavailable: ${reason.name.lowercase()}",
        )
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
}
