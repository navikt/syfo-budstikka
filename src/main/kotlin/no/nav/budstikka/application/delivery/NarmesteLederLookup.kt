package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.NarmesteLederRelasjon

fun interface NarmesteLederLookup {
    /**
     * Returns the active Nærmeste leder relation, or `null` when no active relation exists.
     */
    suspend fun findActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
    ): NarmesteLederRelasjon?
}
