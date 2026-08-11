package no.nav.budstikka.application

import no.nav.budstikka.application.port.AltinnExternalVarsling
import no.nav.budstikka.application.port.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.NarmesteLederExternalVarsling
import no.nav.budstikka.application.port.NarmesteLederLookup
import no.nav.budstikka.application.port.NarmesteLederMissingReason
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Sends ARBEIDSGIVERVARSEL through an [AltinnResource] or a Nærmeste leder path. The latter resolves
 * the active leader at send time, so a deferred delivery follows leader changes without persisting
 * the leader's identifier. External notifications are always sent as LOEPENDE: [SendingWindowGate]
 * has already waited for budstikka's delivery window. A requested external notification without a
 * leader email fails the entire delivery terminally; it does not degrade to in-app only. Closing
 * uses frozen stored CREATE data and its external id. A malformed thin payload or missing frozen
 * external id fails terminally.
 */
class ArbeidsgivervarselChannelHandler(
    private val publisher: ArbeidsgiverNotificationPublisher,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DispatchMetrics,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val create =
            delivery.payload as? ArbeidsgivervarselCreate
                ?: return payloadFailure(delivery)
        return when (delivery.operation) {
            Operation.CREATE -> publishCreate(delivery, create)
            Operation.INACTIVATE -> closeCreate(delivery, create)
        }
    }

    private suspend fun publishCreate(
        delivery: ClaimedDelivery,
        create: ArbeidsgivervarselCreate,
    ): DeliveryOutcome {
        if (create.link.isBlank()) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL link must not be blank")
        }
        val externalId =
            delivery.createExternalId
                ?: (delivery.inboxEventId ?: delivery.id).toString()
        val notificationRecipient =
            when (val recipient = create.recipient) {
                is AltinnResource -> recipient.toNotificationRecipient()
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
                            eksternId = externalId,
                            grupperingsid = create.sakstilknytning?.sakId,
                            tag = create.tag,
                            tekst = create.text,
                            lenke = create.link,
                            recipient = notificationRecipient,
                            meldingstype = create.meldingstype,
                        ),
                    )
                }
        ) {
            ArbeidsgiverNotificationResponse.Published -> DeliveryOutcome.Sent
            is ArbeidsgiverNotificationResponse.Rejected -> DeliveryOutcome.Failed(response.reason)
        }
    }

    private suspend fun closeCreate(
        delivery: ClaimedDelivery,
        create: ArbeidsgivervarselCreate,
    ): DeliveryOutcome {
        val externalId =
            delivery.createExternalId
                ?: return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL inactivate is missing frozen external id")
        return when (
            val response =
                withChannelHandlerFailureContext(Channel.ARBEIDSGIVERVARSEL, "closing notification") {
                    publisher.close(
                        ArbeidsgiverNotificationCloseRequest(
                            eksternId = externalId,
                            tag = create.tag,
                            meldingstype = create.meldingstype,
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
                DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL inactivate must use stored create payload")
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match ARBEIDSGIVERVARSEL channel: ${delivery.payload::class.simpleName}",
                )
        }
}
