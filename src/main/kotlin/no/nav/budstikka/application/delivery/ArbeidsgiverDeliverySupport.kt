package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.EmailBodyFormat
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.AltinnExternalVarsling as AltinnExternalVarslingWire
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient
import no.nav.budstikka.contract.NarmesteLederExternalVarsling as NarmesteLederExternalVarslingWire

/**
 * Stable per dispatch across retries and replicas: the inbox event id, or the delivery id for a
 * derived delivery without one. Used as correlation header and downstream idempotency key.
 */
internal fun ClaimedDelivery.correlationId(): String = (inboxEventId ?: id).toString()

/**
 * Maps an Arbeidsgivernotifikasjoner response to a delivery outcome. [ArbeidsgiverNotificationResponse.NotYetAvailable]
 * throws so the delivery stays claimed and is retried after its lease, within the attempt budget.
 */
internal fun ArbeidsgiverNotificationResponse.toDeliveryOutcome(channel: Channel): DeliveryOutcome =
    when (this) {
        ArbeidsgiverNotificationResponse.Published -> DeliveryOutcome.Sent
        is ArbeidsgiverNotificationResponse.Rejected -> DeliveryOutcome.Failed(reason)
        is ArbeidsgiverNotificationResponse.SakDeleted -> DeliveryOutcome.Failed(reason)
        is ArbeidsgiverNotificationResponse.NotYetAvailable ->
            throw ChannelHandlerFailure(channel, "waiting for a downstream object", IllegalStateException(reason))
    }

/** Result of resolving the active Nærmeste leder for a delivery. */
internal sealed interface LeaderResolution {
    data class Found(
        val relation: NarmesteLederRelasjon,
    ) : LeaderResolution

    data class Missing(
        val outcome: DeliveryOutcome.Failed,
    ) : LeaderResolution
}

/**
 * Resolves the active leader at send time. A missing leader, or a missing email address when
 * [requireEmail] is set, is a terminal, metered failure without identifiers.
 */
internal suspend fun NarmesteLederLookup.resolveForDelivery(
    channel: Channel,
    sykmeldt: PersonIdentifier,
    orgnummer: Orgnummer,
    requireEmail: Boolean,
    metrics: DeliveryMetrics,
): LeaderResolution {
    val relation =
        withChannelHandlerFailureContext(channel, "resolving nærmeste leder") {
            findActive(sykmeldt, orgnummer)
        } ?: return LeaderResolution.Missing(leaderFailure(channel, NarmesteLederMissingReason.MISSING_ACTIVE_LEADER, metrics))
    if (requireEmail && relation.epostadresser.isEmpty()) {
        return LeaderResolution.Missing(leaderFailure(channel, NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS, metrics))
    }
    return LeaderResolution.Found(relation)
}

private fun leaderFailure(
    channel: Channel,
    reason: NarmesteLederMissingReason,
    metrics: DeliveryMetrics,
): DeliveryOutcome.Failed {
    metrics.narmesteLederMissing(reason)
    return DeliveryOutcome.Failed("${channel.name} NarmesteLeder delivery unavailable: ${reason.name.lowercase()}")
}

/** Why the email requested for the leader cannot be sent, or `null`. */
internal fun NarmesteLederRecipient.invalidEmailReason(): String? =
    when {
        externalVarsling?.emailTitle?.isBlank() == true -> "external notification emailTitle must not be blank"
        externalVarsling != null && externalVarsling?.resolvedEmailHtmlBody() == null ->
            "external notification emailText must not be blank"
        else -> null
    }

/** The leader of [relation] as downstream recipient; a requested email goes to the leader's addresses. */
internal fun NarmesteLederRecipient.toNotificationRecipient(relation: NarmesteLederRelasjon) =
    ArbeidsgiverNotificationRecipient.NarmesteLeder(
        narmesteLederFnr = relation.narmesteLederFnr,
        ansattFnr = sykmeldt,
        externalVarsling =
            externalVarsling?.let {
                NarmesteLederExternalVarsling(
                    epostTittel = it.emailTitle,
                    epostHtmlBody = requireNotNull(it.resolvedEmailHtmlBody()),
                    epostadresser = relation.epostadresser,
                )
            },
    )

/** The sak of the leader relation: a new leader gets a sak of their own. */
internal fun NarmesteLederRecipient.toSaksmottaker(relation: NarmesteLederRelasjon) =
    Saksmottaker.NarmesteLeder(relation.relasjonId, relation.narmesteLederFnr, sykmeldt)

internal fun NarmesteLederExternalVarslingWire.resolvedEmailHtmlBody(): String? = resolveEmailHtmlBody(emailText, emailBodyFormat)

internal fun AltinnExternalVarslingWire.resolvedEmailHtmlBody(): String? = resolveEmailHtmlBody(emailText, emailBodyFormat)

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
