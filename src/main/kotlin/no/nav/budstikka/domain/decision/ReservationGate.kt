package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.domain.foundation.ReservationLookup

/** Removes external varsling and adds any reserve Brev when KRR says the person cannot be notified. */
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
