package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Ledervarsel

/**
 * Domenets inngang for å sende et LEDERVARSEL (in-app aktivitetsvarsel i Dine Sykmeldte, B62).
 * Kalleren avhenger av dette – ikke av Kafka, topic eller `DineSykmeldteHendelse`-formatet.
 * Transport og destinasjon bindes ved oppstart. `reference` er OPPRETT↔FERDIGSTILL-koblingen
 * (B39) og brukes som konsumentens `id`/Kafka-key.
 */
fun interface LedervarselPublisher {
    suspend fun publish(
        reference: String,
        ledervarsel: Ledervarsel,
    )
}
