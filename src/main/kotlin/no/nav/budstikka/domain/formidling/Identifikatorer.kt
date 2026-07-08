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
