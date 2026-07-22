package no.nav.budstikka.infrastructure

import kotlin.time.Clock
import kotlin.time.Instant

internal class MutableClock(
    var current: Instant,
) : Clock {
    override fun now(): Instant = current
}
