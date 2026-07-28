package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Microfrontend

/**
 * Domain entry point for controlling microfrontend visibility on Min side (B41). Callers depend on
 * this, not Kafka, topic, or message format. Transport and destination are bound at startup.
 */
fun interface MicrofrontendPublisher {
    suspend fun publish(microfrontend: Microfrontend)
}
