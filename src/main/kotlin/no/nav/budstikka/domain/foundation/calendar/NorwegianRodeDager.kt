package no.nav.budstikka.domain.foundation.calendar

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

internal fun LocalDate.plusDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)

internal fun LocalDate.minusDays(n: Int): LocalDate = minus(n, DateTimeUnit.DAY)

object NorwegianRodeDager {
    @Volatile
    private var cache: Map<Int, Map<LocalDate, String>> = emptyMap()

    /**
     * Påskedag (vestlig/gregoriansk) etter Meeus/Jones/Butcher.
     * Gyldig for hele den gregorianske kalenderen, dvs. fra 1583.
     */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate(year, month, day)
    }

    /** Alle norske røde dager for [year], med navn. */
    fun rodeDager(year: Int): Map<LocalDate, String> {
        cache[year]?.let { return it }
        val computed = compute(year)
        cache = cache + (year to computed)
        return computed
    }

    fun nameOf(date: LocalDate): String? = rodeDager(date.year)[date]

    fun isHoliday(date: LocalDate): Boolean = nameOf(date) != null

    private fun compute(year: Int): Map<LocalDate, String> {
        val easter = easterSunday(year)
        val acc = LinkedHashMap<LocalDate, String>()

        fun add(
            date: LocalDate,
            name: String,
        ) {
            val existing = acc[date]
            acc[date] = if (existing == null) name else "$existing / $name"
        }

        // Faste datoer
        add(LocalDate(year, 1, 1), "Nyttårsdag")
        add(LocalDate(year, 5, 1), "Arbeidernes dag")
        add(LocalDate(year, 5, 17), "Grunnlovsdagen")
        add(LocalDate(year, 12, 25), "Første juledag")
        add(LocalDate(year, 12, 26), "Andre juledag")

        // Bevegelige, avledet av påskedag
        add(easter.minusDays(3), "Skjærtorsdag")
        add(easter.minusDays(2), "Langfredag")
        add(easter, "Første påskedag")
        add(easter.plusDays(1), "Andre påskedag")
        add(easter.plusDays(39), "Kristi himmelfartsdag")
        add(easter.plusDays(49), "Første pinsedag")
        add(easter.plusDays(50), "Andre pinsedag")

        return acc
    }
}
