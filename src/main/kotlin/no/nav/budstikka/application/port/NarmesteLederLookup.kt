package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier

fun interface NarmesteLederLookup {
    /**
     * Returns the active Nærmeste leder relation, or `null` when no active relation exists.
     */
    suspend fun findActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
    ): NarmesteLederRelasjon?
}

data class NarmesteLederRelasjon(
    val narmesteLederFnr: PersonIdentifier,
    val epostadresser: List<String>,
)
