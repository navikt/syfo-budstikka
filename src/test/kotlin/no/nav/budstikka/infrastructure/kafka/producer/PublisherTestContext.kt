package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.infrastructure.config.PlatformConfig

internal class PublisherTestContext {
    val platformConfig =
        PlatformConfig(
            clusterName = "dev-gcp",
            namespace = "team-esyfo",
            appName = "syfo-budstikka",
        )

    val publisher = RecordingMessagePublisher()

    val topic = "min-side.aapen-microfrontend-v1"
}

class RecordingMessagePublisher : MessagePublisher {
    val published = mutableListOf<PublishedMessage>()

    override suspend fun publish(message: PublishedMessage) {
        published += message
    }
}
