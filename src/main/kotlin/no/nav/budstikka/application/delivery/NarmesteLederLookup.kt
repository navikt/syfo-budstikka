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

/** @property relationId identifier assigned by esyfo-narmesteleder to this relation record. */
data class NarmesteLederRelasjon(
    val narmesteLederFnr: PersonIdentifier,
    val epostadresser: List<String>,
    val relationId: UUID,
) {
    override fun toString(): String = "NarmesteLederRelasjon(hasEmailAddresses=${epostadresser.isNotEmpty()})"
}
