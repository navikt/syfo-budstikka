package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import no.nav.budstikka.infrastructure.config.PlatformConfig

internal fun String.parseJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

internal class PublisherFixture {
    val platformConfig =
        PlatformConfig(
            clusterName = "dev-gcp",
            namespace = "team-esyfo",
            appName = "syfo-budstikka",
        )

    val recording = Recording()

    val topic = "min-side.topic"

    class Recording : MessagePublisher {
        val published = mutableListOf<PublishedMessage>()

        override suspend fun publish(message: PublishedMessage) {
            published += message
        }
    }
}
