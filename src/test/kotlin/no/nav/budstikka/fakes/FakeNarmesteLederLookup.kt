package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederRelasjon
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Synthetic id of a leader relation in esyfo-narmesteleder. */
val TEST_LEDERRELASJON_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000aa01")

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
