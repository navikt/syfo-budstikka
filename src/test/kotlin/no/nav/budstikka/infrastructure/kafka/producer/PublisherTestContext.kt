package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.infrastructure.config.PlatformConfig

internal class PublisherTestContext {
    val platformConfig =
        PlatformConfig(
            clusterName = "dev-gcp",
            namespace = "team-esyfo",
            appName = "syfo-budstikka",
        )

    val recording = RecordingMessagePublisher()

    val topic = "min-side.topic"
}

class RecordingMessagePublisher : MessagePublisher {
    val published = mutableListOf<PublishedMessage>()

    override suspend fun publish(message: PublishedMessage) {
        published += message
    }
}
