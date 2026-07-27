package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

/**
 * Person identifier (fødselsnummer, 11 digits). Masked in logs (B9): [toString] always returns
 * `***`, so fnr never leaks through string interpolation or data class `toString`.
 */
@Serializable
@JvmInline
value class PersonIdentifier(
    val value: String,
) {
    override fun toString(): String = MASKED
}

/**
 * Orgnummer (organisation/subunit, 9 digits). Masked in logs (B9), like [PersonIdentifier], as
 * defense in depth against accidental PII leakage.
 */
@Serializable
@JvmInline
value class Orgnummer(
    val value: String,
) {
    override fun toString(): String = MASKED
}

private const val MASKED = "***"

/**
 * Kafka header name that is part of the published contract (shared source for producer and consumer).
 */
object DispatchHeader {
    /**
     * `eventId` as Kafka header (ADR 0008): sole, authoritative, mandatory source. Deduplicate on it
     * (PK, ON CONFLICT DO NOTHING); a missing or invalid header goes to dead letter.
     */
    const val EVENT_ID = "eventId"
}
