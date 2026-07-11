package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.DeathLookup

/**
 * Død-gaten (B-nivå: «ikke send til død person»): dropper en brukerrettet CREATE når mottakeren er
 * registrert som død i PDL. Gaten self-selekterer via [gatedPerson] – lukkeoperasjoner
 * (INACTIVATE), microfrontend og leder-/arbeidsgivervarsel (der mottakeren ikke er den sykmeldte)
 * har ingen gated person og slippes uendret gjennom. PDL kalles kun når hendelsen faktisk kan gates.
 */
internal class DeathGate(
    private val deathLookup: DeathLookup,
) : DecisionRule {
    override suspend fun resolve(event: Dispatch): ResolvedRule {
        val recipientIsDead = event.content.gatedPerson()?.let { deathLookup.isDead(it) } ?: false
        return ResolvedRule { deliveries ->
            if (recipientIsDead) {
                Decision.Dropped(DropReason.DEAD)
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}
