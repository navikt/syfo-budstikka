package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.decision.Channel

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
        is ArbeidsgiverNotificationResponse.CaseDeleted -> DeliveryOutcome.Failed(reason)
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
