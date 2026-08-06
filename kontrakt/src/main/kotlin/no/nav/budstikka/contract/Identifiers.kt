package no.nav.budstikka.contract

import kotlinx.serialization.Serializable

/**
 * Personident whose [toString] is always masked to prevent accidental log disclosure.
 *
 * @property value The 11-digit personident. Treat it as person data and never log it.
 */
@Serializable
@JvmInline
value class PersonIdentifier(
    val value: String,
) {
    override fun toString(): String = MASKED
}

/**
 * Organisation identifier whose [toString] is always masked.
 *
 * @property value The 9-digit organisation number. Treat it as person data and never log it.
 */
@Serializable
@JvmInline
value class Orgnummer(
    val value: String,
) {
    override fun toString(): String = MASKED
}

private const val MASKED = "***"

/** Kafka header name in the published Dispatch contract. */
@InternalBudstikkaWire
object DispatchHeader {
    /**
     * Mandatory event identifier and deduplication key. A missing or invalid value is dead-lettered.
     */
    const val EVENT_ID = "eventId"
}
