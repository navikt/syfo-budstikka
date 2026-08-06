package no.nav.budstikka.domain.foundation

import no.nav.budstikka.contract.PersonIdentifier

fun interface DeathLookup {
    suspend fun isDead(ident: PersonIdentifier): Boolean
}
