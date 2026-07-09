package no.nav.budstikka.infrastructure.kafka.producer

data class PublishedMessage(
    val topic: String,
    val id: String,
    val value: String,
)
