package no.nav.budstikka.domain.foundation

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import no.nav.budstikka.domain.foundation.calendar.openingHours
import kotlin.time.Clock
import kotlin.time.Instant

object BudstikkaSendingWindowLookup {
    private val openingHours =
        openingHours {
            closedOn(DayOfWeek.SUNDAY)
            closedOnRodeDager()
            closedOn(Month.DECEMBER, 24, "Julaften")
            open(LocalTime(9, 0), LocalTime(20, 0))
        }

    fun isClosed(instant: Instant = Clock.System.now()) = !openingHours.isOpen(instant)

    fun reason(instant: Instant = Clock.System.now()) = openingHours.violations(instant)

    fun nextOpen(instant: Instant = Clock.System.now()) = openingHours.opensAt(instant)
}
