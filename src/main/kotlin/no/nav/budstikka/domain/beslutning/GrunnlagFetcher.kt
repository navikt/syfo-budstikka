package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.grunnlag.DeathLookup

/**
 * Grunnlagsinnhenting (B28 steg 1 – imperativt skall, I/O). Slår opp de eksterne fakta den rene
 * [decide] trenger og pakker dem i et immutabelt [Beslutningsgrunnlag]. Nå kun død-gaten via
 * [DeathLookup]; PDL kalles bare når hendelsen faktisk kan gates ([gatedPerson]).
 *
 * KRR-reservasjon og nærmeste-leder-resolusjon (B24) legges til her når de kanalene bygges.
 */
class GrunnlagFetcher(
    private val deathLookup: DeathLookup,
) {
    suspend fun fetch(hendelse: Formidling): Beslutningsgrunnlag {
        val isDead = hendelse.content.gatedPerson()?.let { deathLookup.isDead(it) } ?: false
        return Beslutningsgrunnlag(mottakerIsDead = isDead)
    }
}
