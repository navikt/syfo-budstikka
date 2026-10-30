package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.Personnavn
import no.nav.budstikka.application.delivery.PersonnavnLookup
import no.nav.budstikka.contract.PersonIdentifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Names registered per person; everyone else has none. Counts lookups. */
class FakePersonnavnLookup : PersonnavnLookup {
    private val names = ConcurrentHashMap<PersonIdentifier, Personnavn>()
    private val lookupCount = AtomicInteger()

    val lookups: Int
        get() = lookupCount.get()

    fun register(
        ident: PersonIdentifier,
        navn: Personnavn,
    ) {
        names[ident] = navn
    }

    override suspend fun findName(ident: PersonIdentifier): Personnavn? {
        lookupCount.incrementAndGet()
        return names[ident]
    }
}
