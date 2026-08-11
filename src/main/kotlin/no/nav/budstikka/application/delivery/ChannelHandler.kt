package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery

/**
 * Channel-specific sending behind a narrow interface. [DeliveryWorker] depends on a
 * `Map<Channel, ChannelHandler>`, not concrete publishers, so a new channel is one handler plus
 * registration, without changing the worker.
 */
fun interface ChannelHandler {
    suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome
}

sealed interface DeliveryOutcome {
    data object Sent : DeliveryOutcome

    data class Failed(
        val reason: String,
    ) : DeliveryOutcome
}
