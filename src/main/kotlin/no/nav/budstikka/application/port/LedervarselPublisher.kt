package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Ledervarsel

/**
 * The domain's entry point for sending a LEDERVARSEL (in-app activity notification in Dine Sykmeldte,
 * ADR 0016). The caller depends on this — not on Kafka, the topic or the `DineSykmeldteHendelse`
 * format. Transport and destination are bound at startup. `reference` is the OPPRETT↔FERDIGSTILL link
 * (B39) and is used as the consumer's `id`/Kafka key.
 */
fun interface LedervarselPublisher {
    suspend fun publish(
        reference: String,
        ledervarsel: Ledervarsel,
    )
}
