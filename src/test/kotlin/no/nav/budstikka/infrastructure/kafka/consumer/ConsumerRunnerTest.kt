package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.Heartbeat
import no.nav.budstikka.infrastructure.MutableClock
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConsumerRunnerTest :
    FunSpec({
        test("runner commits handled records and stops cleanly") {
            val partition = TopicPartition(TOPIC, 0)
            val consumer = RecordingMockConsumer()
            val handledOffsets = mutableListOf<Long>()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = perRecordHandler { record -> handledOffsets += record.offset() },
                )

            consumer.schedulePollTask {
                consumer.rebalance(listOf(partition))
                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
            }
            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start()
            runner.join()

            handledOffsets.shouldContainExactly(0L)
            consumer.committedOffsets[partition]?.offset()!!.shouldBeExactly(1L)
            consumer.closed() shouldBe true
        }

        test("runner commits a whole poll batch once at the highest offset") {
            val partition = TopicPartition(TOPIC, 0)
            val consumer = RecordingMockConsumer()
            val handledOffsets = mutableListOf<Long>()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = perRecordHandler { record -> handledOffsets += record.offset() },
                )

            consumer.schedulePollTask {
                consumer.rebalance(listOf(partition))
                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value-0"))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 1L, "key", "value-1"))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 2L, "key", "value-2"))
            }
            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start()
            runner.join()

            handledOffsets.shouldContainExactly(0L, 1L, 2L)
            consumer.committedOffsets[partition]?.offset()!!.shouldBeExactly(3L)
            consumer.commitCount.shouldBeExactly(1L)
        }

        test("runner invokes batch handler once per poll batch") {
            val partition = TopicPartition(TOPIC, 0)
            val consumer = RecordingMockConsumer()
            val handledOffsets = mutableListOf<Long>()
            var batchInvocations = 0
            val batchCapableHandler =
                BatchMessageHandler<String, String> { records ->
                    batchInvocations++
                    handledOffsets += records.map { it.offset() }
                }
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = batchCapableHandler,
                )

            consumer.schedulePollTask {
                consumer.rebalance(listOf(partition))
                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value-0"))
                consumer.addRecord(ConsumerRecord(TOPIC, 0, 1L, "key", "value-1"))
            }
            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start()
            runner.join()

            batchInvocations shouldBe 1
            handledOffsets.shouldContainExactly(0L, 1L)
            consumer.committedOffsets[partition]?.offset()!!.shouldBeExactly(2L)
        }

        test("a failing handler never commits and the record is re-polled on every restart") {
            // Inbox guarantee: a record that cannot be handled must never be dropped. The handler
            // throws forever, so no offset is ever committed and the same record is redelivered by
            // each rebuilt consumer until a human intervenes (visible as consumer lag).
            val partition = TopicPartition(TOPIC, 0)
            val createdConsumers = mutableListOf<RecordingMockConsumer>()
            val handledAttempts = CountDownLatch(2)

            val runner =
                ConsumerRunner(
                    consumerFactory = {
                        RecordingMockConsumer().also { consumer ->
                            createdConsumers += consumer
                            consumer.schedulePollTask {
                                consumer.rebalance(listOf(partition))
                                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                            }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler =
                        perRecordHandler {
                            handledAttempts.countDown()
                            error("persistent boom")
                        },
                )

            runner.start()
            handledAttempts.await(5, TimeUnit.SECONDS) shouldBe true
            runner.stop()
            runner.join()

            createdConsumers.all { it.committedOffsets[partition] == null } shouldBe true
            (createdConsumers.size >= 2) shouldBe true
        }

        test("a batch that fails once is re-polled and committed after it succeeds") {
            val partition = TopicPartition(TOPIC, 0)
            val createdConsumers = mutableListOf<RecordingMockConsumer>()
            val attempts = AtomicInteger(0)

            lateinit var runner: ConsumerRunner<String, String>
            runner =
                ConsumerRunner(
                    consumerFactory = {
                        RecordingMockConsumer().also { consumer ->
                            createdConsumers += consumer
                            consumer.schedulePollTask {
                                consumer.rebalance(listOf(partition))
                                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                            }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler =
                        perRecordHandler {
                            if (attempts.getAndIncrement() == 0) {
                                error("transient boom")
                            }
                            runner.stop()
                        },
                )

            runner.start()
            runner.join(Duration.ofSeconds(5)) shouldBe true

            attempts.get() shouldBe 2
            createdConsumers.first().committedOffsets[partition] shouldBe null
            createdConsumers
                .last()
                .committedOffsets[partition]
                ?.offset()!!
                .shouldBeExactly(1L)
        }

        test("runner rebuilds the consumer and recovers after a transient poll failure") {
            val partition = TopicPartition(TOPIC, 0)
            val createdConsumers = mutableListOf<RecordingMockConsumer>()
            val handledOffsets = mutableListOf<Long>()
            val createdCount = AtomicInteger(0)

            lateinit var runner: ConsumerRunner<String, String>
            runner =
                ConsumerRunner(
                    consumerFactory = {
                        when (createdCount.getAndIncrement()) {
                            0 -> {
                                FailingPollConsumer().also { createdConsumers += it }
                            }

                            else -> {
                                RecordingMockConsumer().also { consumer ->
                                    createdConsumers += consumer
                                    consumer.schedulePollTask {
                                        consumer.rebalance(listOf(partition))
                                        consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                        consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                                    }
                                }
                            }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler =
                        perRecordHandler { record ->
                            handledOffsets += record.offset()
                            runner.stop()
                        },
                )

            runner.start()
            runner.join(Duration.ofSeconds(5)) shouldBe true

            handledOffsets.shouldContainExactly(0L)
            (createdConsumers.size >= 2) shouldBe true
            createdConsumers.all { it.closed() } shouldBe true
        }

        test("a fatal error invokes onFatalError once and does not rebuild the consumer") {
            val createdConsumers = mutableListOf<RecordingMockConsumer>()
            val fatalErrors = mutableListOf<Throwable>()

            val runner =
                ConsumerRunner(
                    consumerFactory = { FatalPollConsumer().also { createdConsumers += it } },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            runner.start { error -> fatalErrors += error }
            runner.join(Duration.ofSeconds(5)) shouldBe true

            createdConsumers.size shouldBe 1
            fatalErrors.size shouldBe 1
            (fatalErrors.single() is AuthenticationException) shouldBe true
            createdConsumers.single().closed() shouldBe true
        }

        test("a fatal consumer factory failure invokes onFatalError and stops the runner") {
            // KafkaConsumer's constructor wraps config errors in KafkaException("Failed to
            // construct kafka consumer"); the runner must classify the wrapped cause as fatal
            // instead of letting the exception escape the restart loop.
            val built = AtomicInteger(0)
            val fatalErrors = mutableListOf<Throwable>()

            val runner =
                ConsumerRunner<String, String>(
                    consumerFactory = {
                        built.incrementAndGet()
                        throw KafkaException(
                            "Failed to construct kafka consumer",
                            ConfigException("No resolvable bootstrap urls"),
                        )
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            runner.start { error -> fatalErrors += error }
            runner.join(Duration.ofSeconds(5)) shouldBe true

            built.get() shouldBe 1
            fatalErrors.size shouldBe 1
            (fatalErrors.single().cause is ConfigException) shouldBe true
        }

        test("a transient consumer factory failure is retried with backoff") {
            val partition = TopicPartition(TOPIC, 0)
            val built = AtomicInteger(0)
            val handledOffsets = mutableListOf<Long>()

            lateinit var runner: ConsumerRunner<String, String>
            runner =
                ConsumerRunner(
                    consumerFactory = {
                        if (built.getAndIncrement() == 0) {
                            error("transient factory boom")
                        }
                        RecordingMockConsumer().also { consumer ->
                            consumer.schedulePollTask {
                                consumer.rebalance(listOf(partition))
                                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                            }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler =
                        perRecordHandler { record ->
                            handledOffsets += record.offset()
                            runner.stop()
                        },
                )

            runner.start()
            runner.join(Duration.ofSeconds(5)) shouldBe true

            built.get() shouldBe 2
            handledOffsets.shouldContainExactly(0L)
        }

        test("liveness stays fresh on empty polls because the heartbeat fires every poll round") {
            // A quiet topic must not look dead: record() runs on every poll, including empty ones.
            // The clock jumps past the stale threshold during the poll, so only the runner's
            // record() can keep liveness green afterward.
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, Duration.ofMinutes(5))
            val consumer = RecordingMockConsumer()
            val polledOnEmptyTopic = CountDownLatch(1)

            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    heartbeat = heartbeat,
                    handler = BatchMessageHandler {},
                )

            consumer.schedulePollTask {
                clock.current = clock.current.plus(Duration.ofMinutes(10))
                polledOnEmptyTopic.countDown()
            }

            runner.start()
            polledOnEmptyTopic.await(5, TimeUnit.SECONDS) shouldBe true
            runner.stop()
            runner.join()

            heartbeat.isAlive() shouldBe true
        }

        test("runner does not invoke onFatalError on a clean stop") {
            val consumer = RecordingMockConsumer()
            var fatalCount = 0

            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start { fatalCount++ }
            runner.join()

            fatalCount shouldBe 0
            consumer.closed() shouldBe true
        }

        test("close without start does nothing and never builds a consumer") {
            val built = AtomicInteger(0)
            val runner =
                ConsumerRunner(
                    consumerFactory = {
                        built.incrementAndGet()
                        RecordingMockConsumer()
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            runner.stop()

            built.get() shouldBe 0
        }

        test("close after start joins the loop and closes the consumer exactly once") {
            val consumer = RecordingMockConsumer()
            val polling = CountDownLatch(1)
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            // Signal once the consumer is actually polling so stop() cannot race the loop startup.
            consumer.schedulePollTask { polling.countDown() }

            runner.start()
            polling.await(5, TimeUnit.SECONDS) shouldBe true
            runner.stop()
            runner.join()

            consumer.closed() shouldBe true
            consumer.closeCount.shouldBeExactly(1L)
        }

        test("join returns false when the loop does not stop within the timeout") {
            val consumer = RecordingMockConsumer()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            runner.start()
            val stoppedInTime = runner.join(Duration.ofMillis(50))

            stoppedInTime shouldBe false

            runner.stop()
            runner.join()
            consumer.closed() shouldBe true
        }

        test("join returns true after a clean stop") {
            val consumer = RecordingMockConsumer()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start()
            val stoppedInTime = runner.join(Duration.ofSeconds(2))

            stoppedInTime shouldBe true
            consumer.closed() shouldBe true
        }

        test("backoff resets after a long-lived consumer fails") {
            // Consumer 1 fails immediately (lifecycle < threshold) → backoff doubles to 100ms.
            // Consumer 2 runs for 2 ms (lifecycle > threshold) → backoff resets to initialBackoff.
            // Consumer 3 fails immediately → delay before it should use reset backoff (~50ms),
            // not the doubled one (~100ms).
            //
            // We verify this by comparing delays: delay(2nd,3rd) should be roughly equal to
            // delay(1st,2nd) (both use ~50ms backoff after reset). Without the reset, the 2nd
            // backoff would be ~100ms, making delay(2nd,3rd) noticeably larger.
            val partition = TopicPartition(TOPIC, 0)
            val createdConsumers = mutableListOf<RecordingMockConsumer>()
            val handlerTimestamps = mutableListOf<Long>()
            val handlerAttempts = CountDownLatch(3)
            val consumerCount = AtomicInteger(0)

            val runner =
                ConsumerRunner(
                    consumerFactory = {
                        val attempt = consumerCount.getAndIncrement()
                        RecordingMockConsumer().also { consumer ->
                            createdConsumers += consumer
                            consumer.schedulePollTask {
                                consumer.rebalance(listOf(partition))
                                consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                if (attempt == 1) {
                                    // Second consumer: sleep so lifecycle exceeds healthyResetThreshold.
                                    Thread.sleep(2)
                                }
                                consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                            }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(50),
                    maxBackoff = Duration.ofMillis(200),
                    healthyResetThreshold = Duration.ofMillis(1),
                    handler =
                        perRecordHandler {
                            handlerTimestamps += System.currentTimeMillis()
                            handlerAttempts.countDown()
                            error("transient boom")
                        },
                )

            runner.start()
            handlerAttempts.await(5, TimeUnit.SECONDS) shouldBe true
            runner.stop()
            runner.join()

            createdConsumers.size shouldBe 3
            createdConsumers.all { it.closed() } shouldBe true
            handlerTimestamps.size shouldBe 3

            val delay1to2 = handlerTimestamps[1] - handlerTimestamps[0]
            val delay2to3 = handlerTimestamps[2] - handlerTimestamps[1]
            // With reset, both delays use ~50ms backoff, so delay2to3 ≈ delay1to2.
            // Without reset, delay2to3 would be ~50ms larger (100ms backoff vs 50ms).
            // Allow 30ms margin for scheduling jitter.
            (delay2to3 < delay1to2 + 30) shouldBe true
        }

        test("start twice throws") {
            val consumer = RecordingMockConsumer()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = BatchMessageHandler {},
                )

            runner.start()
            runCatching { runner.start() }.isFailure shouldBe true
            runner.stop()
        }
    })

private fun perRecordHandler(block: (ConsumerRecord<String, String>) -> Unit): BatchMessageHandler<String, String> =
    BatchMessageHandler { records ->
        records.forEach(block)
    }

private open class RecordingMockConsumer : MockConsumer<String, String>("earliest") {
    val committedOffsets: MutableMap<TopicPartition, OffsetAndMetadata> = mutableMapOf()
    var commitCount: Long = 0L
        private set
    var closeCount: Long = 0L
        private set

    override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) {
        commitCount++
        committedOffsets.putAll(offsets)
        super.commitSync(offsets)
    }

    override fun close() {
        closeCount++
        super.close()
    }
}

private class FailingPollConsumer : RecordingMockConsumer() {
    private val failed = AtomicInteger(0)

    override fun poll(timeout: Duration?): ConsumerRecords<String, String> {
        if (failed.getAndIncrement() == 0) {
            error("transient boom")
        }
        return super.poll(timeout)
    }
}

private class FatalPollConsumer : RecordingMockConsumer() {
    override fun poll(timeout: Duration?): ConsumerRecords<String, String> = throw AuthenticationException("bad credentials")
}
