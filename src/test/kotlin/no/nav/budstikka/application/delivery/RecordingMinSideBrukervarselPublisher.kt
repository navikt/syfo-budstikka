package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Brukervarsel

internal data class PublishedBrukervarsel(
    val reference: String,
    val brukervarsel: Brukervarsel,
)

internal class RecordingMinSideBrukervarselPublisher : MinSideBrukervarselPublisher {
    val published = mutableListOf<PublishedBrukervarsel>()

    override suspend fun publish(
        reference: String,
        brukervarsel: Brukervarsel,
    ) {
        published += PublishedBrukervarsel(reference, brukervarsel)
    }
}
