package no.nav.budstikka.infrastructure.kafka.minside

import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.PublishedMessage

class RecordingMessagePublisher : MessagePublisher {
    val published = mutableListOf<PublishedMessage>()

    override suspend fun publish(message: PublishedMessage) {
        published += message
    }
}
