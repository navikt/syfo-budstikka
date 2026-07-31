package no.nav.budstikka.domain.foundation.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

fun openingHours(block: OpeningHoursBuilder.() -> Unit): OpeningHours =
    OpeningHoursBuilder().apply(block).build()

class OpeningHoursBuilder {
    private val rules = mutableListOf<OpeningRule>()
    var zone: TimeZone = TimeZone.of("Europe/Oslo")
    var horizon: Duration = 30.days

    fun rule(rule: OpeningRule) = apply { rules += rule }
    fun closedOn(vararg days: DayOfWeek) = rule(ClosedOnDays(days.toSet()))
    fun closedOn(month: Month, day: Int, name: String) =
        rule(ClosedOnDates { date -> if (date.month == month && date.day == day) name else null })

    fun closedOnRodeDager() = rule(ClosedOnDates())
    fun open(from: LocalTime, until: LocalTime) = rule(OpenBetween(from, until))

    internal fun build() = OpeningHours(rules.toList(), zone, horizon)
}

class OpeningHours internal constructor(
    private val rules: List<OpeningRule>,
    private val zone: TimeZone,
    private val horizon: Duration,
) {
    fun violations(instant: Instant): List<Closed> {
        val moment = Moment(instant, zone)
        return rules.mapNotNull { it.check(moment) }
    }

    fun isOpen(instant: Instant): Boolean = violations(instant).isEmpty()

    fun opensAt(instant: Instant): Instant =
        if (isOpen(instant)) instant else scanTo(instant) { isOpen(it) }
            ?: error("Ingen åpning funnet innenfor $horizon fra $instant")

    private fun scanTo(from: Instant, stopWhen: (Instant) -> Boolean): Instant? {
        val deadline = from + horizon
        var cursor = from
        repeat(MAX_STEPS) {
            val moment = Moment(cursor, zone)
            val next = rules
                .map { it.nextBoundary(moment) }
                .filter { it > cursor }
                .minOrNull() ?: return null
            if (next > deadline) return null
            if (stopWhen(next)) return next
            cursor = next
        }
        return null
    }

    private companion object {
        const val MAX_STEPS = 1000
    }
}

/** Et tidspunkt sett fra en gitt tidssone. */
class Moment(val instant: Instant, val zone: TimeZone) {
    val local: LocalDateTime = instant.toLocalDateTime(zone)
    val date: LocalDate get() = local.date
    val time: LocalTime get() = local.time
    val dayOfWeek: DayOfWeek get() = local.dayOfWeek

    fun at(date: LocalDate, time: LocalTime): Instant =
        LocalDateTime(date, time).toInstant(zone)

    fun startOfNextDay(): Instant = date.plusDays(1).atStartOfDayIn(zone)
}

/** Grunnen til at det er stengt. */
data class Closed(val rule: String, val reason: String)

fun interface OpeningRule {
    /** null = regelen er tilfreds. */
    fun check(moment: Moment): Closed?

    /**
     * Neste tidspunkt der svaret *kan* endre seg. Må være strengt større enn [Moment.instant].
     * Default: midnatt neste dag — korrekt for alle kalenderbaserte regler.
     */
    fun nextBoundary(moment: Moment): Instant = moment.startOfNextDay()
}

internal class ClosedOnDays(private val days: Set<DayOfWeek>) : OpeningRule {
    override fun check(moment: Moment): Closed? =
        if (moment.dayOfWeek in days) Closed("weekday", "Stengt ${moment.dayOfWeek}") else null
}

internal class ClosedOnDates(
    private val calendar: (LocalDate) -> String? = NorwegianRodeDager::nameOf,
) : OpeningRule {
    override fun check(moment: Moment): Closed? =
        calendar(moment.date)?.let { Closed("holiday", "$it (${moment.date})") }
}

/** Åpent i [from, until) samme døgn. Vinduet kan ikke krysse midnatt. */
internal class OpenBetween(
    private val from: LocalTime,
    private val until: LocalTime,
) : OpeningRule {
    init {
        require(from < until) { "from må være før until" }
    }

    override fun check(moment: Moment): Closed? =
        if (moment.time in from..<until) null
        else Closed("hours", "Åpent $from–$until")

    override fun nextBoundary(moment: Moment): Instant {
        val t = moment.time
        return when {
            t < from -> moment.at(moment.date, from)
            t < until -> moment.at(moment.date, until)
            else -> moment.startOfNextDay()
        }
    }
}
