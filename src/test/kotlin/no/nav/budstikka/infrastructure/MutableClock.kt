package no.nav.budstikka.infrastructure

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A hand-advanced [Clock] for deterministic time-based tests. */
internal class MutableClock(
    var current: Instant,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
