package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery

/**
 * Kanalspesifikk utsending bak et smalt grensesnitt. [DeliveryWorker] avhenger av et
 * `Map<Channel, ChannelHandler>` — ikke av konkrete publishers — så en ny kanal er én handler +
 * registrering, uten å røre workeren.
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
