package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Microfrontend

/** Controls microfrontend visibility on Min side without exposing the transport. */
fun interface MicrofrontendPublisher {
    suspend fun publish(microfrontend: Microfrontend)
}
