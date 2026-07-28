package no.nav.budstikka.fakes

import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.ReservationLookup
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configurable in-memory fake (B52) for [ReservationLookup] — the fake the decision worker can
 * wire into tests/e2e instead of the real KRR adapter. No network, no tokens, full control.
 *
 * By default, no ident has Reservasjon. Mark one with [registerReserved]; [reset] clears state
 * between scenarios. [lookupCount] counts calls, so tests can verify self-selection (the gate does
 * not query KRR when there is nothing to gate).
 */
class FakeReservationLookup : ReservationLookup {
    private val reserved = mutableSetOf<PersonIdentifier>()
    private val calls = AtomicInteger(0)

    /** Number of [isReserved] calls since construction or [reset]. */
    val lookupCount: Int get() = calls.get()

    /** Gives this ident Reservasjon (`kanVarsles=false`) in the fake lookup. */
    fun registerReserved(ident: PersonIdentifier) {
        reserved += ident
    }

    /** Clears all Reservasjon markings and the call counter. */
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
