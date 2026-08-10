package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.PersonIdentifier

fun interface DeathLookup {
    suspend fun isDead(ident: PersonIdentifier): Boolean
}
