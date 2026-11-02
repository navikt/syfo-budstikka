package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.PersonName
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.PersonIdentifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Names registered per person; everyone else has none. Counts lookups. */
class FakePersonNameLookup : PersonNameLookup {
    private val names = ConcurrentHashMap<PersonIdentifier, PersonName>()
    private val lookupCount = AtomicInteger()

    val lookups: Int
        get() = lookupCount.get()

    fun register(
        ident: PersonIdentifier,
        name: PersonName,
    ) {
        names[ident] = name
    }

    override suspend fun findName(ident: PersonIdentifier): PersonName? {
        lookupCount.incrementAndGet()
        return names[ident]
    }
}
