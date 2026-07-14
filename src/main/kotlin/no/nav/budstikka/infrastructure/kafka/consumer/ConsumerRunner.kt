package no.nav.budstikka.infrastructure.kafka.consumer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.Heartbeat
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.lang.invoke.MethodHandles
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a Kafka consumer in its own coroutine and keeps it alive across transient failures.
 *
 * On a transient failure the dead consumer is closed and a fresh one is built (via [consumerFactory])
 * after an exponential backoff, so a broker hiccup or a temporary database outage does not require a
 * process restart. Failures classified as fatal by [isFatal] (bad credentials or configuration) are
 * not retried: they invoke the `onFatalError` callback passed to [start] and stop the runner, leaving
 * the rest of the application (e.g. the HTTP server) untouched.
 *
 * Offsets are committed only after [handler] has handled a full poll batch. If [handler] throws,
 * the exception propagates, the offset is not advanced,
 * and the batch is re-polled from the last commit after a backoff. This is deliberate inbox semantics:
 * a message is never dropped, and a record that can never be handled halts the partition (visible as
 * consumer lag) rather than being silently skipped.
 */
class ConsumerRunner<K, V>(
    private val consumerFactory: () -> Consumer<K, V>,
    private val topics: List<String>,
    private val handler: BatchMessageHandler<K, V>,
    pollTimeout: Duration = 1.seconds,
    val coroutineName: String = "kafka-consumer",
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 30.seconds,
    private val healthyResetThreshold: Duration = 5.minutes,
    private val isFatal: (Throwable) -> Boolean = ::isFatalByDefault,
    private val heartbeat: Heartbeat = Heartbeat(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val pollTimeoutJava: java.time.Duration = java.time.Duration.ofMillis(pollTimeout.inWholeMilliseconds)

    @Volatile
    private var activeConsumer: Consumer<K, V>? = null

    init {
        require(topics.isNotEmpty()) { "topics must not be empty" }
    }

    fun start(onFatalError: (Throwable) -> Unit = {}) {
        check(job == null) { "$coroutineName is already started" }
        running.set(true)
        CoroutineScope(Job() + Dispatchers.IO + CoroutineName(coroutineName)).also { newScope ->
            scope = newScope
            job = newScope.launch(MDCContext(mapOf(MdcKeys.CONSUMER to coroutineName))) { runWithRestart(onFatalError) }
        }
    }

    fun stop() {
        running.set(false)
        activeConsumer?.wakeup()
    }

    /** Liveness signal for this consumer loop; see [Heartbeat] and docs/helsesjekk.md. */
    fun isAlive(): Boolean = heartbeat.isAlive()

    override fun close() {
        MDC.putCloseable(MdcKeys.CONSUMER, coroutineName).use {
            logger.info("Shutdown initiated")
            stop()
            val stopped = join(CLOSE_TIMEOUT_SECONDS.seconds)
            if (!stopped) {
                logger.warn(
                    "{} did not stop within {} seconds",
                    coroutineName,
                    CLOSE_TIMEOUT_SECONDS,
                )
            }
            logger.info("Shutdown complete")
        }
    }

    /**
     * Blocks until the polling coroutine finishes, or until [timeout] elapses if given.
     * Returns true if the coroutine finished, false if the timeout elapsed first.
     */
    fun join(timeout: Duration? = null): Boolean {
        val runnerJob = job ?: return true
        return runBlocking {
            if (timeout == null) {
                runnerJob.join()
                true
            } else {
                withTimeoutOrNull(timeout) { runnerJob.join() } != null
            }
        }
    }

    private suspend fun runWithRestart(onFatalError: (Throwable) -> Unit) {
        var backoffMillis = initialBackoff.inWholeMilliseconds
        while (running.get()) {
            var consumer: Consumer<K, V>? = null
            val lifecycleStart = System.nanoTime()
            try {
                // Inside the try: a throwing factory (e.g. broken consumer config) must go through
                // the same fatal/transient classification as a poll failure — not escape the loop
                // and kill the coroutine without invoking onFatalError.
                consumer = consumerFactory()
                activeConsumer = consumer
                consumer.subscribe(topics)
                pollLoop(consumer)
                // Clean exit: pollLoop only returns when running is false.
            } catch (_: WakeupException) {
                // wakeup() is only called from stop(), so this is a requested shutdown.
            } catch (error: CancellationException) {
                // Cooperative cancellation is not a consumer failure: propagate so the coroutine
                // completes as canceled instead of logging a misleading restart.
                throw error
            } catch (error: Throwable) {
                if (isFatal(error)) {
                    logger.error("{} hit a fatal error and will not restart", coroutineName, error)
                    running.set(false)
                    onFatalError(error)
                } else {
                    val lifecycleNanos = System.nanoTime() - lifecycleStart
                    if (lifecycleNanos > healthyResetThreshold.inWholeNanoseconds) {
                        backoffMillis = initialBackoff.inWholeMilliseconds
                        logger.info(
                            "{} was healthy for {}ms, resetting backoff to {}ms",
                            coroutineName,
                            lifecycleNanos / 1_000_000,
                            backoffMillis,
                        )
                    }
                    logger.warn(
                        "{} failed, restarting after {}ms",
                        coroutineName,
                        backoffMillis,
                        error,
                    )
                }
            } finally {
                // The polling coroutine is the sole owner of each consumer's lifecycle.
                activeConsumer = null
                consumer?.close()
            }

            if (running.get()) {
                backoffDelay(backoffMillis)
                backoffMillis = minOf(backoffMillis * 2, maxBackoff.inWholeMilliseconds)
            }
        }
    }

    // Sleeps in small steps so a stop() requested mid-backoff is observed quickly.
    private suspend fun backoffDelay(millis: Long) {
        var remaining = millis
        while (running.get() && remaining > 0) {
            val step = minOf(remaining, BACKOFF_STEP_MILLIS)
            delay(step.milliseconds)
            remaining -= step
        }
    }

    private suspend fun pollLoop(consumer: Consumer<K, V>) {
        while (running.get()) {
            pollAndHandle(consumer)
        }
    }

    private suspend fun pollAndHandle(consumer: Consumer<K, V>) {
        val records = consumer.poll(pollTimeoutJava)
        // Heartbeat every poll round, including empty ones: a quiet topic must not look dead. A
        // transient broker outage surfaces as empty polls (not exceptions), so liveness stays green,
        // and we avoid coupling the probe to broker availability.
        heartbeat.record()
        if (records.isEmpty) return
        val batch = records.toList()
        handler.handleBatch(batch)
        // Commit the next offset (highest seen + 1) per partition.
        val nextOffsets = batch
            .groupBy { TopicPartition(it.topic(), it.partition()) }
            .mapValues { (_, partitionRecords) -> OffsetAndMetadata(partitionRecords.maxOf { it.offset() } + 1) }
        // commitSync (not commitAsync) on purpose: we commit after handleBatch to keep
        // at-least-once semantics, and commitSync blocks and retries until the offset is
        // persisted. A dropped async commit would widen replay on rebalance/crash; the
        // per-batch commit makes the synchronous cost negligible.
        consumer.commitSync(nextOffsets)
    }

    private companion object {
        const val BACKOFF_STEP_MILLIS = 200L
        const val CLOSE_TIMEOUT_SECONDS = 5L
        const val MAX_CAUSE_DEPTH = 10

        // Walks the cause chain (bounded, in case of a cycle): KafkaConsumer's constructor wraps
        // e.g. ConfigException in KafkaException("Failed to construct kafka consumer"), and a
        // wrapped bad-credentials/config error is just as unrecoverable as a direct one.
        fun isFatalByDefault(error: Throwable): Boolean =
            generateSequence(error, Throwable::cause)
                .take(MAX_CAUSE_DEPTH)
                .any {
                    it is AuthenticationException ||
                        it is AuthorizationException ||
                        it is ConfigException
                }
    }
}
