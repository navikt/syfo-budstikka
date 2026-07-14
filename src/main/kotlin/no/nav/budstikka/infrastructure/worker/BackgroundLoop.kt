package no.nav.budstikka.infrastructure.worker

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.Heartbeat
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * A background loop that runs [iteration] every [interval] until Ktor shuts down and [close]
 * cancels it. The work itself is composed in at the composition root (bootstrap wires an
 * application worker's `runOnce` as [iteration]); this class owns only the lifecycle: coroutine
 * scope, safe error isolation and liveness ([isAlive]).
 *
 * Liveness follows docs/helsesjekk.md: the loop [records][Heartbeat.record] once per round, before
 * the work runs, so an idle or persistently-failing round still counts as "the loop is cycling".
 * Only a hung or dead coroutine goes stale. The stale threshold scales with [interval] (a slow
 * loop, e.g. an hourly cleanup, must not report stale between healthy rounds). A config-broken
 * iteration that spins forever stays alive on purpose — a restart would not help; surface that via
 * logs and metrics, not liveness.
 */
class BackgroundLoop(
    val name: String,
    private val interval: Duration,
    private val heartbeat: Heartbeat = Heartbeat(staleThreshold = defaultStaleThreshold(interval)),
    meterRegistry: MeterRegistry? = null,
    private val iteration: suspend () -> Unit,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    // Per-worker driftsmetrikker (issue #28): runs/varighet/feil, tagget med worker-navnet. Null-
    // registeret gjør dette til ingen-op i tester som ikke bryr seg om måling.
    private val runsCounter: Counter? =
        meterRegistry?.let { Counter.builder("worker.runs").tag("worker", name).register(it) }
    private val failuresCounter: Counter? =
        meterRegistry?.let { Counter.builder("worker.failures").tag("worker", name).register(it) }
    private val durationTimer: Timer? =
        meterRegistry?.let { Timer.builder("worker.duration").tag("worker", name).register(it) }

    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(interval.isPositive()) { "interval must be greater than zero" }
    }

    fun isAlive(): Boolean = heartbeat.isAlive()

    fun start() {
        check(job == null) { "$name is already started" }
        CoroutineScope(Job() + Dispatchers.IO + CoroutineName(name)).also { newScope ->
            scope = newScope
            job = newScope.launch(MDCContext(mapOf(MdcKeys.WORKER to name))) { pollLoop() }
        }
    }

    override fun close() {
        val activeScope = scope ?: return
        val activeJob = job ?: return

        MDC.putCloseable(MdcKeys.WORKER, name).use {
            logger.info("Shutdown initiated")
            activeScope.cancel()
            val stopped = runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_SECONDS.seconds) { activeJob.join() } != null }
            if (!stopped) {
                logger.warn(
                    "{} did not stop within {} seconds",
                    name,
                    CLOSE_TIMEOUT_SECONDS,
                )
            }
            logger.info("Shutdown complete")
        }
        scope = null
        job = null
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            heartbeat.record()
            runIterationSafely()
            delay(interval)
        }
    }

    private suspend fun runIterationSafely() {
        runsCounter?.increment()
        val start = TimeSource.Monotonic.markNow()
        try {
            iteration()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failuresCounter?.increment()
            logger.error("{} failed in iteration", name, error)
        } finally {
            durationTimer?.record(start.elapsedNow().toJavaDuration())
        }
    }

    companion object {
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private const val STALE_THRESHOLD_INTERVALS = 2

        /**
         * Stale threshold derived from the loop interval: at least [Heartbeat.DEFAULT_STALE_THRESHOLD],
         * but never tighter than a couple of missed rounds — so a slow-interval loop (hourly cleanup)
         * is not declared dead between perfectly healthy rounds.
         */
        fun defaultStaleThreshold(interval: Duration): Duration =
            maxOf(interval * STALE_THRESHOLD_INTERVALS, Heartbeat.DEFAULT_STALE_THRESHOLD)
    }
}
