package no.nav.budstikka.domain.formidling

import kotlinx.serialization.Serializable

/**
 * Personident (fødselsnummer, 11 siffer). Maskeres i logg (B9): [toString] gir alltid
 * `***` slik at fnr aldri lekker via strenginterpolasjon eller data class-`toString`.
 */
@Serializable
@JvmInline
value class Personident(
    val value: String,
) {
    override fun toString(): String = MASKERT
}

/**
 * Orgnummer (virksomhet/underenhet, 9 siffer). Maskeres i logg (B9) på samme måte som
 * [Personident] – forsvar-i-dybden mot utilsiktet PII-lekkasje.
 */
@Serializable
@JvmInline
value class Orgnummer(
    val value: String,
) {
    override fun toString(): String = MASKERT
}

private const val MASKERT = "***"

/**
 * Kafka-header-navn som er del av den publiserte kontrakten (delt kilde for produsenter og
 * konsument). Selve header-håndteringen (lesing/validering ved inntak) hører til konsumenten
 * (#19); her defineres kun navnet så begge sider refererer én streng.
 */
object FormidlingHeader {
    /**
     * `eventId` speilet som Kafka-header (samme verdi som [Formidling.eventId] i payloaden).
     * Lar konsumenten dedup-e og lagre rå payload i innboks uten å deserialisere bodyen.
     * Payloaden forblir autoritativ kilde; headeren er en fast-path, ikke en erstatning.
     */
    const val EVENT_ID = "eventId"
}
