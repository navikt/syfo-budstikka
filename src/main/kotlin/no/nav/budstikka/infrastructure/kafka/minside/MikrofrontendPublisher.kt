package no.nav.budstikka.infrastructure.kafka.minside

import no.nav.budstikka.domain.formidling.Mikrofrontend
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.publish

/**
 * Domenets inngang for å styre synlighet av en mikrofrontend på Min side (B41). Kalleren avhenger av
 * dette – ikke av Kafka, topic eller [MikrofrontendHandler]. Transport og destinasjon bindes i
 * [mikrofrontendPublisher] ved oppstart.
 */
fun interface MikrofrontendPublisher {
    suspend fun publish(mikrofrontend: Mikrofrontend)
}

fun mikrofrontendPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
): MikrofrontendPublisher =
    MikrofrontendPublisher { mikrofrontend ->
        messagePublisher.publish(topic, mikrofrontend, MikrofrontendHandler)
    }
