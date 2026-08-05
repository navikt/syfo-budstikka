package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Ledervarsel

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
