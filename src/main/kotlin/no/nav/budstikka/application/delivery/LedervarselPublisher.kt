package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Ledervarsel

/**
 * Sends an activity notification to Dine Sykmeldte. `reference` links creation and inactivation and
 * is the downstream identifier.
 */
fun interface LedervarselPublisher {
    suspend fun publish(
        reference: String,
        ledervarsel: Ledervarsel,
    )
}
