package no.nav.budstikka.infrastructure.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.time.withTimeoutOrNull
import no.nav.budstikka.infrastructure.config.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.lang.invoke.MethodHandles
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

abstract class BaseTask(
    val name: String,
    private val interval: Duration,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(!interval.isNegative && !interval.isZero) { "interval must be greater than zero" }
    }

    fun start() {
        check(job == null) { "$name is already started" }
        running.set(true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(name)).also { newScope ->
            scope = newScope
            job = newScope.launch(MDCContext(mapOf(MdcKeys.TASK to name))) { pollLoop() }
        }
    }

    override fun close() {
        running.set(false)
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
        while (running.get()) {
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
