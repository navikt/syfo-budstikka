package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Brukervarsel

fun interface MinSideBrukervarselPublisher {
    suspend fun publish(
        reference: String,
        brukervarsel: Brukervarsel,
    )
}
