package no.nav.budstikka.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kafka payload envelope. `eventId` exists only in the [DispatchHeader.EVENT_ID] header. */
@InternalBudstikkaWire
@Serializable
data class Dispatch(
    val reference: String,
    val content: DispatchContent,
) {
    /**
     * Omits [reference] because it may identify a notification about a person. The content keeps its
     * variant visible while redacting identifiers and free text.
     */
    override fun toString(): String = "Dispatch(content=$content)"
}

/**
 * Sealed root for contract variants. [partitionKey] is the Kafka record key that preserves ordering
 * for one recipient and is not serialized into the payload.
 */
@InternalBudstikkaWire
@Serializable
sealed interface DispatchContent {
    val partitionKey: String
}

/**
 * Canonical JSON configuration for the [Dispatch] contract. The polymorphic discriminator is `type`
 * (matching `@SerialName` on every variant). `ignoreUnknownKeys` makes additive fields non-breaking
 * for older consumers and versions.
 */
@InternalBudstikkaWire
val dispatchJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
