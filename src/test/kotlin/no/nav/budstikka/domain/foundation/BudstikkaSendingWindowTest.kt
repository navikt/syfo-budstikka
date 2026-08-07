package no.nav.budstikka.domain.foundation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private val Oslo = TimeZone.of("Europe/Oslo")

class BudstikkaSendingWindowTest :
    FunSpec({

        test("isOpen: åpent i åpningstid (tirsdag 12:00)") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 11, 12, 0).toInstant(Oslo)) shouldBe false
        }

        test("isOpen: stengt utenfor åpningstid (mandag 06:00)") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 10, 6, 0).toInstant(Oslo)) shouldBe true
        }

        test("isOpen: stengt søndag") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 9, 12, 0).toInstant(Oslo)) shouldBe true
        }

        test("nextOpen: søndag 10:00 gir mandag 09:00") {
            val instant = LocalDateTime(2025, 2, 9, 10, 0).toInstant(Oslo)
            val next = BudstikkaSendingWindowLookup.nextOpen(instant)
            next shouldNotBeSameInstanceAs instant
            (next - instant).inWholeHours shouldBe 23
        }

        test("nextOpen: allerede åpent returnerer samme instans") {
            val instant = LocalDateTime(2025, 2, 11, 12, 0).toInstant(Oslo)
            BudstikkaSendingWindowLookup.nextOpen(instant) shouldBeSameInstanceAs instant
        }
    })
