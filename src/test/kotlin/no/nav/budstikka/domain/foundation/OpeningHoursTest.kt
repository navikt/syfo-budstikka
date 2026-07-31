package no.nav.budstikka.domain.foundation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import no.nav.budstikka.domain.foundation.calendar.openingHours
import java.time.ZoneId
import java.time.ZonedDateTime

private val oslo = ZoneId.of("Europe/Oslo")

private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    Instant.fromEpochMilliseconds(
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, oslo).toInstant().toEpochMilli()
    )

// Inkluderer julaften 24.12
private val hours = openingHours {
    zone = TimeZone.of("Europe/Oslo")
    closedOn(DayOfWeek.SUNDAY)
    closedOnRodeDager()
    open(LocalTime(8, 0), LocalTime(20, 0))
}

class OpeningHoursTest : FunSpec({

    test("Åpent tirsdag 12:00") {
        hours.isOpen(zdt(2025, 2, 11, 12)) shouldBe true
    }

    test("Stengt før 08 — mandag 06:00") {
        hours.isOpen(zdt(2025, 2, 10, 6)) shouldBe false
    }

    test("Stengt etter 20 — mandag 21:00") {
        hours.isOpen(zdt(2025, 2, 10, 21)) shouldBe false
    }

    test("Stengt søndag") {
        hours.isOpen(zdt(2025, 2, 9, 12)) shouldBe false
    }

    test("Stengt nyttårsdag 1.1") {
        hours.isOpen(zdt(2025, 1, 1, 12)) shouldBe false
    }

    test("Stengt Grunnlovsdag 17.5") {
        hours.isOpen(zdt(2025, 5, 17, 12)) shouldBe false
    }

    test("Stengt langfredag 2025 (18.4)") {
        hours.isOpen(zdt(2025, 4, 18, 12)) shouldBe false
    }

    test("Stengt påskedag 2025 (20.4)") {
        hours.isOpen(zdt(2025, 4, 20, 12)) shouldBe false
    }

    test("Stengt andre påskedag 2025 (21.4)") {
        hours.isOpen(zdt(2025, 4, 21, 12)) shouldBe false
    }

    test("Stengt Kristi himmelfart 2025 (29.5)") {
        hours.isOpen(zdt(2025, 5, 29, 12)) shouldBe false
    }

    test("Stengt pinsedag 2025 (8.6)") {
        hours.isOpen(zdt(2025, 6, 8, 12)) shouldBe false
    }

    test("Stengt andre pinsedag 2025 (9.6)") {
        hours.isOpen(zdt(2025, 6, 9, 12)) shouldBe false
    }

    test("Stengt 1. juledag") {
        hours.isOpen(zdt(2025, 12, 25, 12)) shouldBe false
    }

    test("Stengt 2. juledag") {
        hours.isOpen(zdt(2025, 12, 26, 12)) shouldBe false
    }

    test("Grense 08:00:00 — åpent") {
        hours.isOpen(zdt(2025, 2, 10, 8, 0)) shouldBe true
    }

    test("Grense 20:00:00 — stengt") {
        hours.isOpen(zdt(2025, 2, 10, 20, 0)) shouldBe false
    }

    test("opensAt søndag 10:00 gir tid til mandag 08:00") {
        val opening = hours.opensAt(zdt(2025, 2, 9, 10))
        opening shouldNotBe null
        (opening - zdt(2025, 2, 9, 10)).inWholeHours shouldBe 22
    }

    test("opensAt 03:00 tirsdag gir tid til 08:00 samme dag") {
        val opening = hours.opensAt(zdt(2025, 2, 11, 3))
        opening shouldNotBe null
        (opening - zdt(2025, 2, 11, 3)).inWholeHours shouldBe 5
    }

    test("Påskealgoritme 2024: påskedag = 31. mars") {
        hours.isOpen(zdt(2024, 3, 31, 12)) shouldBe false
        // Andre påskedag 1.4 = tirsdag → også stengt
        hours.isOpen(zdt(2024, 4, 1, 12)) shouldBe false
    }

    test("Påskealgoritme 2025: påskedag = 20. april") {
        hours.isOpen(zdt(2025, 4, 20, 12)) shouldBe false
    }

    test("Påskealgoritme 2026: påskedag = 5. april") {
        hours.isOpen(zdt(2026, 4, 5, 12)) shouldBe false
        // Andre påskedag 6.4 = torsdag → også stengt
        hours.isOpen(zdt(2026, 4, 6, 12)) shouldBe false
    }
})
