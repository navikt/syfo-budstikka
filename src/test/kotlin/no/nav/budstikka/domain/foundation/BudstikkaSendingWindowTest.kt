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

        test("lørdag er stengt og åpner mandag kl. 09:00") {
            val saturday = LocalDateTime(2025, 2, 8, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(saturday) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 2, 10, 9, 0).toInstant(Oslo)
        }

        test("fredag stenger kl. 20:00 og åpner igjen mandag") {
            val beforeClosing = LocalDateTime(2025, 2, 7, 19, 59).toInstant(Oslo)
            val closing = LocalDateTime(2025, 2, 7, 20, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(beforeClosing) shouldBe false
            BudstikkaSendingWindowLookup.isClosed(closing) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(closing) shouldBe LocalDateTime(2025, 2, 10, 9, 0).toInstant(Oslo)
        }

        test("påskelørdag åpner tirsdag etter andre påskedag") {
            val saturday = LocalDateTime(2025, 4, 19, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 4, 22, 9, 0).toInstant(Oslo)
        }

        test("julaften åpner først etter juledagene og helgen") {
            val christmasEve = LocalDateTime(2025, 12, 24, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.isClosed(christmasEve) shouldBe true
            BudstikkaSendingWindowLookup.nextOpen(christmasEve) shouldBe LocalDateTime(2025, 12, 29, 9, 0).toInstant(Oslo)
        }

        test("helg med sommertid åpner kl. 09:00 i Oslo") {
            val saturday = LocalDateTime(2025, 3, 29, 12, 0).toInstant(Oslo)

            BudstikkaSendingWindowLookup.nextOpen(saturday) shouldBe LocalDateTime(2025, 3, 31, 9, 0).toInstant(Oslo)
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
