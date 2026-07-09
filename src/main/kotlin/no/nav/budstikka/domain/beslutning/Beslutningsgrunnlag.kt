package no.nav.budstikka.domain.beslutning

/**
 * Immutabelt beslutningsgrunnlag (resultatet av B28 steg 1 – grunnlagsinnhenting) som den rene
 * [decide] fatter beslutning på. Nå bærer det kun død-gaten; KRR-reservasjon og nærmeste-leder-
 * resolusjon (B24) legges til additivt når de kanalene bygges (#22/#23).
 */
data class Beslutningsgrunnlag(
    val mottakerErDod: Boolean = false,
)
