package no.nav.budstikka.infrastructure

import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-reported liveness signal for a single background loop — a Kafka consumer or a [task][no.nav.budstikka.infrastructure.task.BackgroundLoop]
 * (see docs/HELSESJEKK.md). The loop calls [record] every round, including idle ones, so a quiet loop
 * is not mistaken for a dead one. [isAlive] reports stale once no round has run within [staleThreshold]
 * — i.e. the loop has hung or exited. Never couple this to broker availability, downstream health,
 * processing success or lag; those belong in metrics and alerts.
 *
 * State is a lock-free [AtomicReference]: the loop coroutine writes, the HTTP liveness handler reads.
 * [Clock] is injectable so the stale threshold can be unit-tested with a fake clock.
 */
class Heartbeat(
    private val clock: Clock = Clock.systemUTC(),
    private val staleThreshold: Duration = DEFAULT_STALE_THRESHOLD,
) {
    private val lastBeat = AtomicReference(clock.instant())

    fun record() {
        lastBeat.set(clock.instant())
    }

    fun isAlive(): Boolean = Duration.between(lastBeat.get(), clock.instant()) <= staleThreshold

    companion object {
        // Larger than loop frequency plus max processing time so a slow-but-healthy round does not
        // trigger a false restart; safe default for low-volume loops.
        val DEFAULT_STALE_THRESHOLD: Duration = Duration.ofMinutes(5)
    }
}
