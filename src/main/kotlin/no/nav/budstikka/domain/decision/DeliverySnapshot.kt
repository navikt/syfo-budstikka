package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName

/** Explicit provenance: a resolved null name never enables legacy register lookups. */
sealed interface DeliverySnapshot {
    data object Legacy : DeliverySnapshot

    data class Resolved(
        val leader: NarmesteLederRelasjon? = null,
        val name: PersonName? = null,
    ) : DeliverySnapshot {
        override fun toString(): String = "Resolved(hasLeader=${leader != null}, hasName=${name != null})"
    }
}
