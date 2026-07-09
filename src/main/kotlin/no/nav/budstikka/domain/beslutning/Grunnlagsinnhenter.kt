package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.grunnlag.DodsfallOppslag

/**
 * Grunnlagsinnhenting (B28 steg 1 – imperativt skall, I/O). Slår opp de eksterne fakta den rene
 * [decide] trenger og pakker dem i et immutabelt [Beslutningsgrunnlag]. Nå kun død-gaten via
 * [DodsfallOppslag]; PDL kalles bare når hendelsen faktisk kan gates ([gatetPerson]).
 *
 * KRR-reservasjon og nærmeste-leder-resolusjon (B24) legges til her når de kanalene bygges.
 */
class Grunnlagsinnhenter(
    private val dodsfall: DodsfallOppslag,
) {
    suspend fun innhent(hendelse: Formidling): Beslutningsgrunnlag {
        val erDod = hendelse.innhold.gatetPerson()?.let { dodsfall.erDod(it) } ?: false
        return Beslutningsgrunnlag(mottakerErDod = erDod)
    }
}
