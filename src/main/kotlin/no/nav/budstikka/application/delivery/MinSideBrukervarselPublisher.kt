package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Brukervarsel

fun interface MinSideBrukervarselPublisher {
    suspend fun publish(
        reference: String,
        brukervarsel: Brukervarsel,
    )
}
