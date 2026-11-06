package no.nav.budstikka.application.delivery

import no.nav.budstikka.domain.decision.Channel

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
