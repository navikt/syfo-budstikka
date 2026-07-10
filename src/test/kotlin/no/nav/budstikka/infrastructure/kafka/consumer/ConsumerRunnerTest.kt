package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthenticationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TOPIC = "team-esyfo.formidling.v1"

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
                    handler =
                        MessageHandler { record ->
                            handledOffsets += record.offset()
                        },
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
                    handler =
                        MessageHandler { record ->
                            handledOffsets += record.offset()
                        },
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
                        MessageHandler {
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
                        MessageHandler {
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
                            0 -> FailingPollConsumer().also { createdConsumers += it }
                            else ->
                                RecordingMockConsumer().also { consumer ->
                                    createdConsumers += consumer
                                    consumer.schedulePollTask {
                                        consumer.rebalance(listOf(partition))
                                        consumer.updateBeginningOffsets(mapOf(partition to 0L))
                                        consumer.addRecord(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                                    }
                                }
                        }
                    },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    initialBackoff = Duration.ofMillis(1),
                    maxBackoff = Duration.ofMillis(1),
                    handler =
                        MessageHandler { record ->
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
                    handler = MessageHandler {},
                )

            runner.start { error -> fatalErrors += error }
            runner.join(Duration.ofSeconds(5)) shouldBe true

            createdConsumers.size shouldBe 1
            fatalErrors.size shouldBe 1
            (fatalErrors.single() is AuthenticationException) shouldBe true
            createdConsumers.single().closed() shouldBe true
        }

        test("liveness stays fresh on empty polls because the heartbeat fires every poll round") {
            // A quiet topic must not look dead: recordPoll() runs on every poll, including empty ones.
            // The clock jumps past the stale threshold during the poll, so only the runner's
            // recordPoll() can keep liveness green afterwards.
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = ConsumerHeartbeat(clock, Duration.ofMinutes(5))
            val consumer = RecordingMockConsumer()
            val polledOnEmptyTopic = CountDownLatch(1)

            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    heartbeat = heartbeat,
                    handler = MessageHandler {},
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
                    handler = MessageHandler {},
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
                    handler = MessageHandler {},
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
                    handler = MessageHandler {},
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
                    handler = MessageHandler {},
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
                    handler = MessageHandler {},
                )

            consumer.schedulePollTask {
                runner.stop()
            }

            runner.start()
            val stoppedInTime = runner.join(Duration.ofSeconds(2))

            stoppedInTime shouldBe true
            consumer.closed() shouldBe true
        }

        test("start twice throws") {
            val consumer = RecordingMockConsumer()
            val runner =
                ConsumerRunner(
                    consumerFactory = { consumer },
                    topics = listOf(TOPIC),
                    pollTimeout = Duration.ofMillis(1),
                    handler = MessageHandler {},
                )

            runner.start()
            runCatching { runner.start() }.isFailure shouldBe true
            runner.stop()
        }
    })

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
