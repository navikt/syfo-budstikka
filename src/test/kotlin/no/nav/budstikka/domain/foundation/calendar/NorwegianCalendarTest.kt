package no.nav.budstikka.domain.foundation.calendar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

class NorwegianCalendarTest : FunSpec({

    test("easterSunday 2024 gir 31. mars") {
        NorwegianRodeDager.easterSunday(2024) shouldBe LocalDate(2024, 3, 31)
    }

    test("easterSunday 2025 gir 20. april") {
        NorwegianRodeDager.easterSunday(2025) shouldBe LocalDate(2025, 4, 20)
    }

    test("easterSunday 2026 gir 5. april") {
        NorwegianRodeDager.easterSunday(2026) shouldBe LocalDate(2026, 4, 5)
    }

    test("holidays(2025) returnerer 12 røde dager") {
        NorwegianRodeDager.rodeDager(2025).size shouldBe 12
    }

    test("holidays(2025) inneholder alle faste datoer med korrekte navn") {
        val holidays = NorwegianRodeDager.rodeDager(2025)

        holidays[LocalDate(2025, 1, 1)] shouldBe "Nyttårsdag"
        holidays[LocalDate(2025, 5, 1)] shouldBe "Arbeidernes dag"
        holidays[LocalDate(2025, 5, 17)] shouldBe "Grunnlovsdagen"
        holidays[LocalDate(2025, 12, 25)] shouldBe "Første juledag"
        holidays[LocalDate(2025, 12, 26)] shouldBe "Andre juledag"
    }

    test("holidays(2025) inneholder alle bevegelige datoer med korrekte navn") {
        val holidays = NorwegianRodeDager.rodeDager(2025)

        holidays[LocalDate(2025, 4, 17)] shouldBe "Skjærtorsdag"
        holidays[LocalDate(2025, 4, 18)] shouldBe "Langfredag"
        holidays[LocalDate(2025, 4, 20)] shouldBe "Første påskedag"
        holidays[LocalDate(2025, 4, 21)] shouldBe "Andre påskedag"
        holidays[LocalDate(2025, 5, 29)] shouldBe "Kristi himmelfartsdag"
        holidays[LocalDate(2025, 6, 8)] shouldBe "Første pinsedag"
        holidays[LocalDate(2025, 6, 9)] shouldBe "Andre pinsedag"
    }

    test("nameOf returnerer navn for helligdag") {
        NorwegianRodeDager.nameOf(LocalDate(2025, 1, 1)) shouldBe "Nyttårsdag"
        NorwegianRodeDager.nameOf(LocalDate(2025, 4, 20)) shouldBe "Første påskedag"
    }

    test("nameOf returnerer null for vanlig dag") {
        NorwegianRodeDager.nameOf(LocalDate(2025, 2, 10)).shouldBeNull()
    }

    test("isHoliday returnerer true for helligdag") {
        NorwegianRodeDager.isHoliday(LocalDate(2025, 1, 1)) shouldBe true
        NorwegianRodeDager.isHoliday(LocalDate(2025, 4, 20)) shouldBe true
    }

    test("isHoliday returnerer false for vanlig dag") {
        NorwegianRodeDager.isHoliday(LocalDate(2025, 2, 10)) shouldBe false
    }

    test("isHoliday returnerer false for julaften") {
        NorwegianRodeDager.isHoliday(LocalDate(2025, 12, 24)) shouldBe false
    }

    test("holidays cacher resultat: to kall gir samme referanse") {
        val first = NorwegianRodeDager.rodeDager(2027)
        val second = NorwegianRodeDager.rodeDager(2027)
        (first === second) shouldBe true
    }
})
