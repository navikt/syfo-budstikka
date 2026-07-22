package no.nav.budstikka.fakes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FakeReservationLookupTest :
    FunSpec({
        val notReserved = TEST_SYKMELDT
        val reserved = TEST_SYKMELDT_2

        test("default: no one is reserved") {
            FakeReservationLookup().isReserved(notReserved) shouldBe false
        }

        test("registerReserved() marks an ident as reserved") {
            val fake = FakeReservationLookup()
            fake.registerReserved(reserved)
            fake.isReserved(reserved) shouldBe true
        }

        test("registerReserved() does not affect other idents") {
            val fake = FakeReservationLookup()
            fake.registerReserved(reserved)
            fake.isReserved(notReserved) shouldBe false
        }

        test("reset() clears registrations and the call counter") {
            val fake = FakeReservationLookup()
            fake.registerReserved(reserved)
            fake.isReserved(reserved)
            fake.reset()
            fake.isReserved(reserved) shouldBe false
            fake.lookupCount shouldBe 1
        }

        test("lookupCount counts calls") {
            val fake = FakeReservationLookup()
            fake.isReserved(notReserved)
            fake.isReserved(reserved)
            fake.lookupCount shouldBe 2
        }
    })
