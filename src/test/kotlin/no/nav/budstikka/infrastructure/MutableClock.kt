package no.nav.budstikka.infrastructure

import kotlin.time.Clock
import kotlin.time.Instant

/** A hand-advanced [Clock] for deterministic time-based tests. */
internal class MutableClock(
    var current: Instant,
) : Clock {
    override fun now(): Instant = current
}
