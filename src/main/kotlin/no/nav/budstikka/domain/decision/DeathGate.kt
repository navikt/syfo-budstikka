package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.DeathLookup

/**
 * DeathGate (B-level: “do not send to a dead person”): drops a Sykmeldt-directed CREATE when the
 * Recipient is registered as dead in PDL. The gate self-selects through [gatedPerson]: closure
 * operations (INACTIVATE), Microfrontend, Ledervarsel, and Arbeidsgivervarsel (where the Recipient
 * is not the Sykmeldt) have no gated person and pass unchanged. Call PDL only when a Dispatch can be gated.
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
