package no.nav.budstikka.application

import no.nav.budstikka.infrastructure.database.delivery.ClaimedDelivery

/**
 * Kanalspesifikk utsending bak et smalt grensesnitt (B27). [DeliveryTask] avhenger av et
 * `Map<Channel, ChannelHandler>` — ikke av konkrete publishers — så en ny kanal er én handler +
 * registrering, uten å røre workeren.
 *
 * Kontrakt for utfall:
 * - [DeliveryOutcome.Sent]: utsendt; workeren markerer raden SENT.
 * - [DeliveryOutcome.Failed]: permanent feil (f.eks. ugyldig payload); workeren markerer FAILED.
 * - Transient feil signaliseres ved å kaste: raden blir stående CLAIMED og plukkes opp på nytt når
 *   leasen utløper (ADR 0004).
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
