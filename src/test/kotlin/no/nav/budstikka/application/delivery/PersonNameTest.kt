package no.nav.budstikka.application.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PersonNameTest :
    FunSpec({
        test("capitalises each part of a name registered in upper case") {
            PersonName("OLA", null, "NORDMANN").fullName shouldBe "Ola Nordmann"
        }

        test("capitalises each part of a hyphenated name") {
            PersonName("ANNE-MARIE", null, "NORDMANN-HANSEN").fullName shouldBe "Anne-Marie Nordmann-Hansen"
        }

        test("places the middle name between first and last name") {
            PersonName("kari", "anne-marie", "nordmann").fullName shouldBe "Kari Anne-Marie Nordmann"
        }

        test("capitalises every word when a part holds several words") {
            PersonName("ANNE MARIE", "VAN DER", "BERG").fullName shouldBe "Anne Marie Van Der Berg"
        }

        test("leaves out a blank middle name") {
            PersonName("OLA", " ", "NORDMANN").fullName shouldBe "Ola Nordmann"
        }
    })
