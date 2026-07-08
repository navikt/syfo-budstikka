package no.nav.budstikka.fakes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.Personident
import no.nav.budstikka.infrastructure.pdl.FakeDodsfall

class FakeDodsfallTest :
    FunSpec({
        val levende = Personident("11111111111")
        val dod = Personident("22222222222")

        test("default: ingen er død") {
            FakeDodsfall().erDod(levende) shouldBe false
        }

        test("marker() gjør en ident død") {
            val fake = FakeDodsfall()
            fake.marker(dod)
            fake.erDod(dod) shouldBe true
        }

        test("marker() påvirker ikke andre identer") {
            val fake = FakeDodsfall()
            fake.marker(dod)
            fake.erDod(levende) shouldBe false
        }

        test("nullstill() fjerner alle markeringer") {
            val fake = FakeDodsfall()
            fake.marker(dod)
            fake.nullstill()
            fake.erDod(dod) shouldBe false
        }
    })
