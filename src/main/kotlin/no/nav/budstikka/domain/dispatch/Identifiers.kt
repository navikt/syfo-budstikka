package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

/** Personident whose [toString] is always masked to prevent accidental log disclosure. */
@Serializable
@JvmInline
value class PersonIdentifier(
    val value: String,
) {
    override fun toString(): String = MASKED
}

/** Organisation identifier whose [toString] is always masked. */
@Serializable
@JvmInline
value class Orgnummer(
    val value: String,
) {
    override fun toString(): String = MASKED
}

private const val MASKED = "***"

object DispatchHeader {
    /**
     * Mandatory event identifier and deduplication key. A missing or invalid value is dead-lettered.
     */
    const val EVENT_ID = "eventId"
}
