package no.nav.budstikka.application.port

import no.nav.budstikka.contract.Brukervarsel

fun interface MinSideBrukervarselPublisher {
    suspend fun publish(
        reference: String,
        brukervarsel: Brukervarsel,
    )
}
