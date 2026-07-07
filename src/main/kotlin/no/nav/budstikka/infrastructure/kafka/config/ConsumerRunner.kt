package no.nav.budstikka.infrastructure.kafka.config

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeoutOrNull
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

fun interface MessageHandler<K, V> {
    suspend fun handle(record: ConsumerRecord<K, V>)
}

/**
 * Runs a Kafka consumer in its own coroutine and keeps it alive across transient failures.
 *
 * On a transient failure the dead consumer is closed and a fresh one is built (via [consumerFactory])
 * after an exponential backoff, so a broker hiccup or a temporary database outage does not require a
 * process restart. Failures classified as fatal by [isFatal] (bad credentials or configuration) are
 * not retried: they invoke the `onFatalError` callback passed to [start] and stop the runner, leaving
 * the rest of the application (e.g. the HTTP server) untouched.
 *
 * Offsets are committed only after every record in a poll batch has been handled. If [handler] throws,
 * the exception propagates, the offset is not advanced, and the batch is re-polled from the last commit
 * after a backoff. This is deliberate inbox semantics: a message is never dropped, and a record that can
 * never be handled halts the partition (visible as consumer lag) rather than being silently skipped.
 */
class ConsumerRunner<K, V>(
    private val consumerFactory: () -> Consumer<K, V>,
    private val topics: List<String>,
    private val handler: MessageHandler<K, V>,
    private val pollTimeout: Duration = Duration.ofSeconds(1),
    private val coroutineName: String = "kafka-consumer",
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
    private val isFatal: (Throwable) -> Boolean = ::isFatalByDefault,
    private val heartbeat: ConsumerHeartbeat = ConsumerHeartbeat(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

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
            job = newScope.launch { runWithRestart(onFatalError) }
        }
    }

    fun stop() {
        running.set(false)
        activeConsumer?.wakeup()
    }

    /** Liveness signal for this consumer loop; see [ConsumerHeartbeat] and docs/HELSESJEKK.md. */
    fun isAlive(): Boolean = heartbeat.isAlive()

    override fun close() {
        stop()
        val stopped = join(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS))
        if (!stopped) {
            logger.warn(
                "{} did not stop within {} seconds",
                coroutineName,
                CLOSE_TIMEOUT_SECONDS,
            )
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
        var backoffMillis = initialBackoff.toMillis()
        while (running.get()) {
            val consumer = consumerFactory()
            activeConsumer = consumer
            try {
                consumer.subscribe(topics)
                pollLoop(consumer)
                // Clean exit: pollLoop only returns when running is false.
            } catch (_: WakeupException) {
                // wakeup() is only called from stop(), so this is a requested shutdown.
            } catch (error: Throwable) {
                if (isFatal(error)) {
                    logger.error("{} hit a fatal error and will not restart", coroutineName, error)
                    running.set(false)
                    onFatalError(error)
                } else {
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
                consumer.close()
            }

            if (running.get()) {
                backoffDelay(backoffMillis)
                backoffMillis = minOf(backoffMillis * 2, maxBackoff.toMillis())
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
        val records = consumer.poll(pollTimeout)
        // Heartbeat every poll round, including empty ones: a quiet topic must not look dead. A
        // transient broker outage surfaces as empty polls (not exceptions), so liveness stays green
        // and we avoid coupling the probe to broker availability.
        heartbeat.recordPoll()
        if (records.isEmpty) return
        val maxOffsets = mutableMapOf<TopicPartition, Long>()
        for (record in records) {
            // A throw here propagates out of the poll loop: the offset is not committed and
            // runWithRestart re-polls the same batch after a backoff. The inbox never drops a message.
            handler.handle(record)
            maxOffsets.merge(
                TopicPartition(record.topic(), record.partition()),
                record.offset() + 1,
            ) { current, new -> maxOf(current, new) }
        }
        consumer.commitSync(maxOffsets.mapValues { OffsetAndMetadata(it.value) })
    }

    private companion object {
        const val BACKOFF_STEP_MILLIS = 200L
        const val CLOSE_TIMEOUT_SECONDS = 5L

        fun isFatalByDefault(error: Throwable): Boolean =
            error is AuthenticationException ||
                error is AuthorizationException ||
                error is ConfigException
    }
}
