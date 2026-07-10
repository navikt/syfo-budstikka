package no.nav.budstikka.fakes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.pdl.FakeDeathLookup

class FakeDeathLookupTest :
    FunSpec({
        val alive = PersonIdentifier("11111111111")
        val dead = PersonIdentifier("22222222222")

        test("default: ingen er død") {
            FakeDeathLookup().isDead(alive) shouldBe false
        }

        test("mark() gjør en ident død") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.isDead(dead) shouldBe true
        }

        test("mark() påvirker ikke andre identer") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.isDead(alive) shouldBe false
        }

        test("reset() fjerner alle markeringer") {
            val fake = FakeDeathLookup()
            fake.registerDeath(dead)
            fake.reset()
            fake.isDead(dead) shouldBe false
        }
    })
