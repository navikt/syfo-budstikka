package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.NarmesteLederLookup
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import java.util.concurrent.ConcurrentHashMap

class FakeNarmesteLederLookup : NarmesteLederLookup {
    private val activeRelations = ConcurrentHashMap<Key, NarmesteLederRelasjon>()

    fun registerActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
        relation: NarmesteLederRelasjon,
    ) {
        activeRelations[Key(sykmeldt, orgnummer)] = relation
    }

    override suspend fun findActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
    ): NarmesteLederRelasjon? = activeRelations[Key(sykmeldt, orgnummer)]

    private data class Key(
        val sykmeldt: PersonIdentifier,
        val orgnummer: Orgnummer,
    )
}
