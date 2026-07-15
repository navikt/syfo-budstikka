package no.nav.budstikka.application

import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.dispatch.Brukervarsel

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
