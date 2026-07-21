package no.nav.budstikka.fakes

import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.DeathLookup

class FakeDeathLookup : DeathLookup {
    private val dead = mutableSetOf<PersonIdentifier>()

    fun registerDeath(ident: PersonIdentifier) {
        dead += ident
    }

    fun reset() {
        dead.clear()
    }

    override suspend fun isDead(ident: PersonIdentifier): Boolean = ident in dead
}
