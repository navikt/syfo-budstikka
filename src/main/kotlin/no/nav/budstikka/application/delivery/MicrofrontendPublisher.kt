package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Microfrontend

/** Controls microfrontend visibility on Min side without exposing the transport. */
fun interface MicrofrontendPublisher {
    suspend fun publish(microfrontend: Microfrontend)
}
