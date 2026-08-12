package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.contract.Ledervarsel

internal data class PublishedLedervarsel(
    val reference: String,
    val ledervarsel: Ledervarsel,
)

internal class RecordingLedervarselPublisher : LedervarselPublisher {
    val published = mutableListOf<PublishedLedervarsel>()

    override suspend fun publish(
        reference: String,
        ledervarsel: Ledervarsel,
    ) {
        published += PublishedLedervarsel(reference, ledervarsel)
    }
}
