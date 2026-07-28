package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.ReservationLookup

/**
 * B7/B8, ADR 0009: controls `ekstern varsling` and `BrevFallback` for a Brukervarsel CREATE based
 * on whether its recipient can receive a digital notification (KRR, [ReservationLookup]).
 */
internal class ReservationGate(
    private val reservationLookup: ReservationLookup,
) : DecisionRule {
    override suspend fun resolve(event: Dispatch): ResolvedRule {
        val brukervarsel = event.content as? BrukervarselCreate
        val reserved =
            brukervarsel != null &&
                brukervarsel.hasExternalReach() &&
                reservationLookup.isReserved(brukervarsel.personIdentifier)
        return ResolvedRule { deliveries ->
            if (reserved) {
                Decision.Processed(deliveries.flatMap { it.applyReservation() })
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}

private fun BrukervarselCreate.hasExternalReach(): Boolean = externalVarsling != null || brevFallback != null

private fun DeliveryDraft.applyReservation(): List<DeliveryDraft> {
    val brukervarsel = content as? BrukervarselCreate ?: return listOf(this)
    val inAppOnly = copy(content = brukervarsel.copy(externalVarsling = null))
    return listOfNotNull(inAppOnly, brukervarsel.brevFallbackDraft(reference))
}
