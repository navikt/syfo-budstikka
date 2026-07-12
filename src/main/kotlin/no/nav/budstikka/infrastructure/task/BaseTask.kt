package no.nav.budstikka.infrastructure.task

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
import kotlinx.coroutines.time.withTimeoutOrNull
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.Heartbeat
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Base for a background loop that runs [runIteration] every [interval] until Ktor shuts down and
 * [close] cancels it. Subclasses (inbox, delivery, cleanup) only implement one round of work; this
 * base owns the lifecycle: coroutine scope, safe error isolation and liveness ([isAlive]).
 *
 * Liveness follows docs/HELSESJEKK.md: the loop [records][Heartbeat.record] once per round, before
 * the work runs, so an idle or persistently-failing round still counts as "the loop is cycling".
 * Only a hung or dead coroutine goes stale. A config-broken task that spins forever stays alive on
 * purpose — a restart would not help; surface that via logs and metrics, not liveness.
 */
abstract class BaseTask(
    val name: String,
    private val interval: Duration,
    private val heartbeat: Heartbeat = Heartbeat(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(!interval.isNegative && !interval.isZero) { "interval must be greater than zero" }
    }

    fun isAlive(): Boolean = heartbeat.isAlive()

    fun start() {
        check(job == null) { "$name is already started" }
        CoroutineScope(Job() + Dispatchers.IO + CoroutineName(name)).also { newScope ->
            scope = newScope
            job = newScope.launch(MDCContext(mapOf(MdcKeys.TASK to name))) { pollLoop() }
        }
    }

    override fun close() {
        val activeScope = scope ?: return
        val activeJob = job ?: return

        MDC.putCloseable(MdcKeys.TASK, name).use {
            logger.info("Shutdown initiated")
            activeScope.cancel()
            val stopped = runBlocking { withTimeoutOrNull(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)) { activeJob.join() } != null }
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
            delay(interval.toMillis().milliseconds)
        }
    }

    private suspend fun runIterationSafely() {
        try {
            runIteration()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("{} failed in runIteration", name, error)
        }
    }

    protected abstract suspend fun runIteration()

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}
