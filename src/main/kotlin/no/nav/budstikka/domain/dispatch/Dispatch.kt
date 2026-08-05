package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kafka payload envelope. `eventId` exists only in the [DispatchHeader.EVENT_ID] header. */
@Serializable
data class Dispatch(
    val reference: String,
    val content: DispatchContent,
)

/**
 * Sealed root for contract variants. [partitionKey] is the Kafka record key that preserves ordering
 * for one recipient and is not serialized into the payload.
 */
@Serializable
sealed interface DispatchContent {
    val partitionKey: String
}

/**
 * Canonical JSON configuration for the [Dispatch] contract. The polymorphic discriminator is `type`
 * (matching `@SerialName` on every variant). `ignoreUnknownKeys` makes additive fields non-breaking
 * for older consumers and versions.
 */
val dispatchJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
