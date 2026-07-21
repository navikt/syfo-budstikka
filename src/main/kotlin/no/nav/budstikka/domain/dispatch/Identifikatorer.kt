package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

/**
 * Personident (fødselsnummer, 11 siffer). Maskeres i logg (B9): [toString] gir alltid
 * `***` slik at fnr aldri lekker via strenginterpolasjon eller data class-`toString`.
 */
@Serializable
@JvmInline
value class PersonIdentifier(
    val value: String,
) {
    override fun toString(): String = MASKED
}

/**
 * Orgnummer (virksomhet/underenhet, 9 siffer). Maskeres i logg (B9) på samme måte som
 * [PersonIdentifier] – forsvar-i-dybden mot utilsiktet PII-lekkasje.
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
 * Kafka-header-navn som er del av den publiserte kontrakten (delt kilde for produsenter og
 * konsument). Selve header-håndteringen (lesing/validering ved inntak) hører til konsumenten;
 * her defineres kun navnet så begge sider refererer én streng.
 */
object DispatchHeader {
    /**
     * `eventId` som Kafka-header (ADR 0008 / B61). Headeren er den ENESTE kilden til eventId og
     * er autoritativ og obligatorisk: konsumenten deduper på den (PK, `ON CONFLICT DO NOTHING`)
     * FØR payloaden parses, så dedup er skjema-uavhengig. Mangler/ugyldig header → dead-letter,
     * ingen payload-fallback (eventId er fjernet fra [Dispatch]-payloaden).
     */
    const val EVENT_ID = "eventId"
}
