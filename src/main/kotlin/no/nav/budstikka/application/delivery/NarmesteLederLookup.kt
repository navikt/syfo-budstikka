package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import java.util.UUID

fun interface NarmesteLederLookup {
    /**
     * Returns the active Nærmeste leder relation, or `null` when no active relation exists.
     */
    suspend fun findActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
    ): NarmesteLederRelasjon?
}

/**
 * @property relasjonId the leader relation's id in esyfo-narmesteleder. It identifies this leader for
 *   this Sykmeldt and organisation, so content can be grouped per relation without a personident.
 */
data class NarmesteLederRelasjon(
    val narmesteLederFnr: PersonIdentifier,
    val epostadresser: List<String>,
    val relasjonId: UUID,
) {
    override fun toString(): String = "NarmesteLederRelasjon(hasEmailAddresses=${epostadresser.isNotEmpty()})"
}
