package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.DeathLookup

/**
 * Grunnlagsinnhenting (B28 steg 1 – imperativt skall, I/O). Slår opp de eksterne fakta den rene
 * [decide] trenger og pakker dem i et immutabelt [DecisionFoundation]. Nå kun død-gaten via
 * [DeathLookup]; PDL kalles bare når hendelsen faktisk kan gates ([gatedPerson]).
 *
 * KRR-reservasjon og nærmeste-leder-resolusjon (B24) legges til her når de kanalene bygges.
 */
class FoundationFetcher(
    private val deathLookup: DeathLookup,
) {
    suspend fun fetch(event: Dispatch): DecisionFoundation {
        val isDead = event.content.gatedPerson()?.let { deathLookup.isDead(it) } ?: false
        return DecisionFoundation(recipientIsDead = isDead)
    }
}
