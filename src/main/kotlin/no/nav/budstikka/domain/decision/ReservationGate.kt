package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.ReservationLookup

/**
 * KRR-reservasjonsgaten (B7/B8, ADR 0009): styrer ekstern varsling og brev-fallback for en
 * brukervarsel-CREATE ut fra om mottakeren kan varsles digitalt (KRR, [ReservationLookup]).
 *
 * Gaten self-selekterer via [hasExternalReach] – bare et [BrukervarselCreate] med noe eksternt å
 * styre (ekstern varsling eller brev-fallback) slår opp KRR. Andre varianter, lukkeoperasjoner og
 * rene in-app-brukervarsler har ingenting å gate og slippes uendret gjennom UTEN kall (B55).
 *
 * Er mottakeren reservert (kan ikke varsles digitalt) gjør den rene [ResolvedRule.apply] to ting:
 * 1. **Undertrykker ekstern varsling:** brukervarselets `externalVarsling` fjernes. In-app-varselet
 *    vises på Min side uansett (B7) – kun SMS/e-post faller bort.
 * 2. **Legger til brev:** finnes en `brevFallback`, syntetiseres en BREV-leveranse
 *    ([brevFallbackDraft]) som gjenbruker hele BREV-stien (#21).
 *
 * Gaten dropper og feiler aldri selv – transient KRR-feil kastes fra [resolve] (I/O) og håndteres av
 * skallet med backoff, aldri stille tolket som «ikke reservert» (samme disiplin som `DeathGate`/PDL).
 * Rekkefølge i regel-lista: `DeathGate` FØR denne, så en død mottaker short-circuiter til `Dropped`
 * før reservasjonstransformasjonen anvendes (ingen brev til død person).
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

/** Har brukervarselet noe eksternt å styre (og dermed noe KRR-oppslaget kan endre)? */
private fun BrukervarselCreate.hasExternalReach(): Boolean = externalVarsling != null || brevFallback != null

/**
 * Reservasjons-transformasjon av én leveranse: brukervarselet beholdes som in-app (uten ekstern
 * varsling) og får eventuelt følge av en brev-leveranse; andre leveranser slippes uendret gjennom.
 */
private fun DeliveryDraft.applyReservation(): List<DeliveryDraft> {
    val brukervarsel = content as? BrukervarselCreate ?: return listOf(this)
    val inAppOnly = copy(content = brukervarsel.copy(externalVarsling = null))
    return listOfNotNull(inAppOnly, brukervarsel.brevFallbackDraft(reference))
}
