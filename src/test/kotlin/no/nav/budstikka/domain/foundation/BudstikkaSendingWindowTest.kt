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

        test("isOpen: open within opening hours (Tuesday 12:00)") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 11, 12, 0).toInstant(Oslo)) shouldBe false
        }

        test("isOpen: closed outside opening hours (Monday 06:00)") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 10, 6, 0).toInstant(Oslo)) shouldBe true
        }

        test("isOpen: closed on Sunday") {
            BudstikkaSendingWindowLookup.isClosed(LocalDateTime(2025, 2, 9, 12, 0).toInstant(Oslo)) shouldBe true
        }

        test("Saturday is closed and opens Monday at 09:00") {
            val saturday = LocalDateTime(2025, 2, 8, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(saturday) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 2, 10, 9, 0).toInstant(Oslo)
        }

        test("Friday closes at 20:00 and opens again on Monday") {
            val beforeClosing = LocalDateTime(2025, 2, 7, 19, 59).toInstant(Oslo)
            val closing = LocalDateTime(2025, 2, 7, 20, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(beforeClosing) shouldBe false
            BudstikkaSendingWindowLookup.isClosed(closing) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(closing) shouldBe LocalDateTime(2025, 2, 10, 9, 0).toInstant(Oslo)
        }

        test("Easter Saturday opens on Tuesday after Easter Monday") {
            val saturday = LocalDateTime(2025, 4, 19, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 4, 22, 9, 0).toInstant(Oslo)
        }

        test("Christmas Eve opens only after Christmas and the weekend") {
            val christmasEve = LocalDateTime(2025, 12, 24, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(christmasEve) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(christmasEve) shouldBe LocalDateTime(2025, 12, 29, 9, 0).toInstant(Oslo)
        }

        test("a weekend with daylight saving time opens at 09:00 in Oslo") {
            val saturday = LocalDateTime(2025, 3, 29, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 3, 31, 9, 0).toInstant(Oslo)
        }

        test("nextOpen: Sunday 10:00 gives Monday 09:00") {
            val instant = LocalDateTime(2025, 2, 9, 10, 0).toInstant(Oslo)
            val next = BudstikkaSendingWindowLookup.nextOpen(instant)
            next shouldNotBeSameInstanceAs instant
            (next - instant).inWholeHours shouldBe 23
        }

        test("nextOpen: already open returns the same instant") {
            val instant = LocalDateTime(2025, 2, 11, 12, 0).toInstant(Oslo)
            BudstikkaSendingWindowLookup.nextOpen(instant) shouldBeSameInstanceAs instant
        }
    })
