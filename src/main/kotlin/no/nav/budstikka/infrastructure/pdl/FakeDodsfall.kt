package no.nav.budstikka.infrastructure.pdl

import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.domain.grunnlag.DodsfallOppslag

/**
 * In-memory, styrbar fake (B52) av [no.nav.budstikka.domain.grunnlag.DodsfallOppslag] – «mock-klienten» beslutnings-workeren
 * (#20) kan wire i test/e2e i stedet for den ekte PDL-adapteren. Ingen nett, ingen tokens,
 * full kontroll.
 *
 * Default: ingen er død. Marker en ident som død med [marker]; [nullstill] tømmer tilstanden
 * mellom scenarier.
 */
class FakeDodsfall : DodsfallOppslag {
    private val dode = mutableSetOf<Personident>()

    /** «Gjør denne personen død» i dette fake-oppslaget. */
    fun marker(ident: Personident) {
        dode += ident
    }

    /** Nullstill alle markeringer (tilbake til «ingen død»). */
    fun nullstill() {
        dode.clear()
    }

    override suspend fun erDod(ident: Personident): Boolean = ident in dode
}
