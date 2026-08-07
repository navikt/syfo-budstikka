package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.domain.foundation.DeathLookup

/** Drops a sykmeldt-directed creation when PDL reports the person as dead. */
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
