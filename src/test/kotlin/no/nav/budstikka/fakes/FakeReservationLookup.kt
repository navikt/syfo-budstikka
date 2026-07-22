package no.nav.budstikka.fakes

import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.ReservationLookup
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory, styrbar fake (B52) av [ReservationLookup] – «mock-klienten» beslutnings-workeren kan
 * wire i test/e2e i stedet for den ekte KRR-adapteren. Ingen nett, ingen tokens, full kontroll.
 *
 * Default: ingen er reservert. Marker en ident som reservert (kan ikke varsles digitalt) med
 * [registerReserved]; [reset] tømmer tilstanden mellom scenarier. [lookupCount] teller kall, så
 * tester kan verifisere self-selection (at gaten IKKE slår opp KRR når det ikke er noe å gate).
 */
class FakeReservationLookup : ReservationLookup {
    private val reserved = mutableSetOf<PersonIdentifier>()
    private val calls = AtomicInteger(0)

    /** Antall [isReserved]-kall siden opprettelse/[reset]. */
    val lookupCount: Int get() = calls.get()

    /** «Gjør denne personen reservert» (kan ikke varsles digitalt) i dette fake-oppslaget. */
    fun registerReserved(ident: PersonIdentifier) {
        reserved += ident
    }

    /** Nullstill alle markeringer og kall-telleren (tilbake til «ingen reservert»). */
    fun reset() {
        reserved.clear()
        calls.set(0)
    }

    override suspend fun isReserved(ident: PersonIdentifier): Boolean {
        calls.incrementAndGet()
        return ident in reserved
    }
}

fun reservedLookupFor(identifier: PersonIdentifier) = FakeReservationLookup().apply { registerReserved(identifier) }
