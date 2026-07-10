package no.nav.budstikka.fakes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.PersonIdentifier

class FakeDeathLookupTest :
    FunSpec({
        val alive = PersonIdentifier("11111111111")
        val dead = PersonIdentifier("22222222222")

        test("default: no one is dead") {
            FakeDeathLookup().isDead(alive) shouldBe false
        }

        test("registerDeath() marks an ident as dead") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.isDead(dead) shouldBe true
        }

        test("registerDeath() does not affect other idents") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.isDead(alive) shouldBe false
        }

        test("reset() clears all registrations") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.reset()
            fake.isDead(dead) shouldBe false
        }
    })
