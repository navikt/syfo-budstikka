package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Microfrontend

/**
 * Domenets inngang for å styre synlighet av en microfrontend på Min side (B41). Kalleren avhenger av
 * dette – ikke av Kafka, topic eller meldingsformatet. Transport og destinasjon bindes i
 * ved oppstart.
 */
fun interface MicrofrontendPublisher {
    suspend fun publish(microfrontend: Microfrontend)
}
