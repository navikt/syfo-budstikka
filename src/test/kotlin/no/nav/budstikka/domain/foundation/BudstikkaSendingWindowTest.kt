package no.nav.budstikka.domain.foundation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val oslo = ZoneId.of("Europe/Oslo")

private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    Instant.fromEpochMilliseconds(
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, oslo).toInstant().toEpochMilli()
    )

class BudstikkaSendingWindowTest : FunSpec({

    test("isOpen: åpent i åpningstid (tirsdag 12:00)") {
        BudstikkaSendingWindow.isOpen(zdt(2025, 2, 11, 12)) shouldBe true
    }

    test("isOpen: stengt utenfor åpningstid (mandag 06:00)") {
        BudstikkaSendingWindow.isOpen(zdt(2025, 2, 10, 6)) shouldBe false
    }

    test("isOpen: stengt søndag") {
        BudstikkaSendingWindow.isOpen(zdt(2025, 2, 9, 12)) shouldBe false
    }

    test("nextOpen: søndag 10:00 gir mandag 08:00") {
        val next = BudstikkaSendingWindow.nextOpen(zdt(2025, 2, 9, 10))
        next shouldNotBe null
        (next!! - zdt(2025, 2, 9, 10)).inWholeHours shouldBe 22
    }

    test("nextOpen: allerede åpent gir null") {
        BudstikkaSendingWindow.nextOpen(zdt(2025, 2, 11, 12)) shouldBe null
    }
})
