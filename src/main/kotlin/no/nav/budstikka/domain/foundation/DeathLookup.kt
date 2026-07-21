package no.nav.budstikka.domain.foundation

import no.nav.budstikka.domain.dispatch.PersonIdentifier

fun interface DeathLookup {
    suspend fun isDead(ident: PersonIdentifier): Boolean
}
