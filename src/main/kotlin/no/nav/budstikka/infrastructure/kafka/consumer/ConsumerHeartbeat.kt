package no.nav.budstikka.infrastructure.kafka.consumer

import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-reported liveness signal for a single consumer loop (see docs/HELSESJEKK.md).
 *
 * The polling coroutine calls [recordPoll] on every poll round, including empty ones, so a quiet
 * topic does not look dead. The HTTP liveness handler reads [isAlive]. A loop that has crashed or
 * exited stops updating the timestamp, so it eventually reports stale and the pod is restarted.
 *
 * State is a lock-free [AtomicReference]: the consumer coroutine writes, the HTTP handler reads.
 * [Clock] is injectable so the stale threshold can be unit-tested with a fake clock.
 */
class ConsumerHeartbeat(
    private val clock: Clock = Clock.systemUTC(),
    private val staleThreshold: Duration = DEFAULT_STALE_THRESHOLD,
) {
    private val lastPoll = AtomicReference(clock.instant())

    fun recordPoll() {
        lastPoll.set(clock.instant())
    }

    fun isAlive(): Boolean = Duration.between(lastPoll.get(), clock.instant()) <= staleThreshold

    companion object {
        // Larger than poll frequency plus max processing time so a slow-but-healthy batch does not
        // trigger a false restart; safe default for low-volume topics.
        val DEFAULT_STALE_THRESHOLD: Duration = Duration.ofMinutes(5)
    }
}
