package no.nav.budstikka.fakes

import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier

val TEST_SYKMELDT = PersonIdentifier("11111111111")
val TEST_SYKMELDT_2 = PersonIdentifier("12345678901")
val TEST_ORGNUMMER = Orgnummer("987654321")

fun deadLookupFor(identifier: PersonIdentifier) = FakeDeathLookup().apply { registerDeath(identifier) }
