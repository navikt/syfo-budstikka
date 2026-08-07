package no.nav.budstikka.fakes

import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.foundation.ReservationLookup
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configurable [ReservationLookup]. [lookupCount] lets tests verify that irrelevant events skip the
 * lookup.
 */
class FakeReservationLookup : ReservationLookup {
    private val reserved = mutableSetOf<PersonIdentifier>()
    private val calls = AtomicInteger(0)

    val lookupCount: Int get() = calls.get()

    fun registerReserved(ident: PersonIdentifier) {
        reserved += ident
    }

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
