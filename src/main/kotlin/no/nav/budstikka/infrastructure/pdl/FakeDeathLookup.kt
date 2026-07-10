package no.nav.budstikka.infrastructure.pdl

import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.DeathLookup

/**
 * In-memory, styrbar fake (B52) av [no.nav.budstikka.domain.foundation.DeathLookup] – «mock-klienten» beslutnings-workeren
 * (#20) kan wire i test/e2e i stedet for den ekte PDL-adapteren. Ingen nett, ingen tokens,
 * full kontroll.
 *
 * Default: ingen er død. Marker en ident som død med [registerDeath]; [reset] tømmer tilstanden
 * mellom scenarier.
 */
class FakeDeathLookup : DeathLookup {
    private val dead = mutableSetOf<PersonIdentifier>()

    /** «Gjør denne personen død» i dette fake-oppslaget. */
    fun registerDeath(ident: PersonIdentifier) {
        dead += ident
    }

    /** Nullstill alle markeringer (tilbake til «ingen død»). */
    fun reset() {
        dead.clear()
    }

    override suspend fun isDead(ident: PersonIdentifier): Boolean = ident in dead
}
