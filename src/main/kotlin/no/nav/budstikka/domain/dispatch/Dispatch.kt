package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Envelope for the neutral Kafka contract (B22, B43). `eventId` is not part of the payload; it
 * exists only as the Kafka header [DispatchHeader.EVENT_ID] (ADR 0008).
 */
@Serializable
data class Dispatch(
    val reference: String,
    val content: DispatchContent,
)

/**
 * Sealed root for all content (B22). Operation (CREATE/FERDIGSTILL/activate) is encoded in the
 * type, so the compiler enforces B21. [partitionKey] is the Kafka record key (B5) = recipient ID,
 * keeping events for one recipient ordered on the same partition. The getter has no backing field
 * and is therefore not serialised.
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
