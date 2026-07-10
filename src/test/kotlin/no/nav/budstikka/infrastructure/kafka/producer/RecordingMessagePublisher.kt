package no.nav.budstikka.infrastructure.kafka.producer

class RecordingMessagePublisher : MessagePublisher {
    val published = mutableListOf<PublishedMessage>()

    override suspend fun publish(message: PublishedMessage) {
        published += message
    }
}
