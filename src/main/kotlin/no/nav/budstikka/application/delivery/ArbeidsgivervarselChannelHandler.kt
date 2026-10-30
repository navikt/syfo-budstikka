package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.EmailBodyFormat
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Sends ARBEIDSGIVERVARSEL through an [AltinnResource] or a Nærmeste leder path. The latter resolves
 * the active leader at send time, so a deferred delivery follows leader changes without persisting
 * the leader's identifier. External notifications are always sent as LOEPENDE: [SendingWindowGate]
 * has already waited for budstikka's delivery window. A requested external notification without a
 * leader email fails the entire delivery terminally; it does not degrade to in-app only. With a
 * saktype, the notification goes in the Budstikka-owned case of that type for the recipient; without
 * one, it keeps the producer's tag and optional case link. Closing remains a permanent failure.
 */
class ArbeidsgivervarselChannelHandler(
    private val publisher: ArbeidsgiverNotificationPublisher,
    private val saker: Arbeidsgiversaker,
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
        val saktype = create.saktype
        if (saktype == null && create.tag.isNullOrBlank()) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL tag must not be blank")
        }
        if (saktype != null && create.tag != null) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL tag must not be set with saktype")
        }
        if (saktype == null && create.saksstatus != null) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL saksstatus needs a saktype")
        }
        var saksmottaker: Saksmottaker? = null
        val notificationRecipient =
            when (val recipient = create.recipient) {
                is AltinnResource ->
                    if (recipient.resource.isBlank()) {
                        return DeliveryOutcome.Failed(
                            "ARBEIDSGIVERVARSEL recipient.resource must not be blank",
                        )
                    } else if (recipient.externalVarsling?.emailTitle?.isBlank() == true) {
                        return invalidExternalNotification("emailTitle")
                    } else if (recipient.externalVarsling?.smsText?.isBlank() == true) {
                        return invalidExternalNotification("smsText")
                    } else if (recipient.externalVarsling?.resolvedEmailHtmlBody() == null && recipient.externalVarsling != null) {
                        return invalidExternalEmailBody()
                    } else if (saktype != null && (recipient.sykmeldt == null || recipient.sakId.isNullOrBlank())) {
                        return DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL recipient.sykmeldt and recipient.sakId must be set with saktype")
                    } else if (
                        saktype != null &&
                        recipient.sykmeldt?.let { Saksmottaker.AltinnRessurs.isValidSakId(recipient.sakId.orEmpty(), it) } != true
                    ) {
                        return DeliveryOutcome.Failed(
                            "ARBEIDSGIVERVARSEL recipient.sakId must be 1-64 letters, digits, '-' or '_', and not a personident",
                        )
                    } else {
                        saksmottaker =
                            recipient.sykmeldt?.let { sykmeldt ->
                                recipient.sakId?.let { Saksmottaker.AltinnRessurs(recipient.resource, sykmeldt, it) }
                            }
                        recipient.toNotificationRecipient()
                    }
                is NarmesteLederRecipient -> {
                    if (recipient.externalVarsling?.emailTitle?.isBlank() == true) {
                        return invalidExternalNotification("emailTitle")
                    }
                    if (recipient.externalVarsling?.resolvedEmailHtmlBody() == null && recipient.externalVarsling != null) {
                        return invalidExternalEmailBody()
                    }
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
                    saksmottaker = Saksmottaker.NarmesteLeder(relation.relasjonId, relation.narmesteLederFnr, recipient.sykmeldt)
                    ArbeidsgiverNotificationRecipient.NarmesteLeder(
                        narmesteLederFnr = relation.narmesteLederFnr,
                        ansattFnr = recipient.sykmeldt,
                        externalVarsling =
                            recipient.externalVarsling?.let {
                                NarmesteLederExternalVarsling(
                                    epostTittel = it.emailTitle,
                                    epostHtmlBody = requireNotNull(it.resolvedEmailHtmlBody()),
                                    epostadresser = relation.epostadresser,
                                )
                            },
                    )
                }
            }

        val sak =
            saktype?.let {
                when (
                    val resolution =
                        saker.ensure(
                            channel = Channel.ARBEIDSGIVERVARSEL,
                            saktype = it,
                            virksomhetsnummer = create.orgnummer.value,
                            mottaker = requireNotNull(saksmottaker),
                            correlationId = delivery.correlationId(),
                            activityAt = create.visibleUntil,
                        )
                ) {
                    is SakResolution.Failed -> return resolution.outcome
                    is SakResolution.Ready -> resolution
                }
            }
        val request =
            ArbeidsgiverNotificationRequest(
                virksomhetsnummer = create.orgnummer.value,
                eksternId = (delivery.inboxEventId ?: delivery.id).toString(),
                grupperingsid = sak?.grupperingsid ?: create.sakstilknytning?.sakId,
                tag = sak?.merkelapp ?: requireNotNull(create.tag),
                tekst = create.text,
                lenke = create.link,
                recipient = notificationRecipient,
                meldingstype = create.meldingstype,
                visibleUntil = create.visibleUntil,
            )
        val outcome = publish(request).toDeliveryOutcome(Channel.ARBEIDSGIVERVARSEL)
        val status = create.saksstatus
        if (outcome != DeliveryOutcome.Sent || sak == null || status == null) return outcome
        return saker.setStatus(Channel.ARBEIDSGIVERVARSEL, sak, status, delivery.correlationId()) ?: outcome
    }

    private suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        withChannelHandlerFailureContext(Channel.ARBEIDSGIVERVARSEL, "publishing notification") {
            publisher.publish(request)
        }

    private fun AltinnResource.toNotificationRecipient() =
        ArbeidsgiverNotificationRecipient.AltinnRessurs(
            resource = resource,
            externalVarsling =
                externalVarsling?.let {
                    AltinnExternalVarsling(
                        epostTittel = it.emailTitle,
                        epostHtmlBody = requireNotNull(it.resolvedEmailHtmlBody()),
                        smsTekst = it.smsText,
                    )
                },
        )

    private fun invalidExternalEmailBody() =
        DeliveryOutcome.Failed(
            "ARBEIDSGIVERVARSEL external notification emailText must not be blank",
        )

    private fun invalidExternalNotification(field: String) =
        DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL external notification $field must not be blank")

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

internal fun no.nav.budstikka.contract.NarmesteLederExternalVarsling.resolvedEmailHtmlBody(): String? =
    resolveEmailHtmlBody(emailText, emailBodyFormat)

private fun no.nav.budstikka.contract.AltinnExternalVarsling.resolvedEmailHtmlBody(): String? =
    resolveEmailHtmlBody(emailText, emailBodyFormat)

private fun resolveEmailHtmlBody(
    emailText: String,
    emailBodyFormat: EmailBodyFormat?,
): String? {
    if (emailText.isBlank()) return null
    return when (emailBodyFormat) {
        EmailBodyFormat.HTML -> emailText
        null -> emailText.toEscapedHtml()
    }
}

private fun String.toEscapedHtml(): String =
    buildString {
        this@toEscapedHtml
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        '\n' -> "<br>"
                        else -> character
                    },
                )
            }
    }
