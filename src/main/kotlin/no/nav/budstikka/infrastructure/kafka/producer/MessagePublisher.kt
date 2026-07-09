package no.nav.budstikka.infrastructure.kafka.producer

fun interface MessagePublisher {
    suspend fun publish(message: PublishedMessage)
}
