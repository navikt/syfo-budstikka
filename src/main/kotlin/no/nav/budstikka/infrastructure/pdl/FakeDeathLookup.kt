package no.nav.budstikka.infrastructure.pdl

import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.grunnlag.DeathLookup

/**
 * In-memory, styrbar fake (B52) av [no.nav.budstikka.domain.grunnlag.DeathLookup] – «mock-klienten» beslutnings-workeren
 * (#20) kan wire i test/e2e i stedet for den ekte PDL-adapteren. Ingen nett, ingen tokens,
 * full kontroll.
 *
 * Default: ingen er død. Marker en ident som død med [mark]; [reset] tømmer tilstanden
 * mellom scenarier.
 */
class FakeDeathLookup : DeathLookup {
    private val dead = mutableSetOf<Personident>()

    /** «Gjør denne personen død» i dette fake-oppslaget. */
    fun mark(ident: Personident) {
        dead += ident
    }

    /** Nullstill alle markeringer (tilbake til «ingen død»). */
    fun reset() {
        dead.clear()
    }

    override suspend fun isDead(ident: Personident): Boolean = ident in dead
}
