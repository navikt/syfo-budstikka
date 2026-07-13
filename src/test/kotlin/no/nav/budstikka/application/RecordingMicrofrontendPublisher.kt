package no.nav.budstikka.application

import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.dispatch.Microfrontend

internal class RecordingMicrofrontendPublisher : MicrofrontendPublisher {
    val published = mutableListOf<Microfrontend>()

    override suspend fun publish(microfrontend: Microfrontend) {
        published += microfrontend
    }
}
