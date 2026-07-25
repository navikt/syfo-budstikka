package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.clients.producer.RoundRobinPartitioner
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.milliseconds

class MessagePublisherTest :
    FunSpec({
        test("publishes the topic, key and value to Kafka") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = MessagePublisherImpl { producer }
            val message =
                publishedMessage(
                    topic = "min-side.aapen-microfrontend-v1",
                    id = "12345678901",
                    value = """{"type":"MicrofrontendEnable"}""",
                )
            publisher.publish(message)

            with(producer.history().single()) {
                topic() shouldBe message.topic
                key() shouldBe message.id
                value() shouldBe message.value
            }
        }

        test("does not create producer when closed before first publish") {
            var createdProducers = 0
            val publisher =
                MessagePublisherImpl {
                    createdProducers++
                    MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
                }

            publisher.close()

            createdProducers shouldBe 0
        }

        test("does not create producer when publishing after close") {
            var createdProducers = 0
            val publisher =
                MessagePublisherImpl {
                    createdProducers++
                    MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
                }

            publisher.close()
            shouldThrow<IllegalStateException> {
                publisher.publish(
                    publishedMessage(),
                )
            }

            createdProducers shouldBe 0
        }

        test("closes producer during cleanup after it has been used") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = MessagePublisherImpl { producer }

            publisher.publish(
                publishedMessage(),
            )
            publisher.close()

            producer.closed() shouldBe true
        }

        test("propagates kafka send exception") {
            val exception = RuntimeException("broker unavailable")
            val delegate = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val failingProducer =
                object : Producer<String, String> by delegate {
                    override fun send(
                        record: ProducerRecord<String, String>,
                        callback: Callback?,
                    ): Future<RecordMetadata> = throw exception
                }
            val publisher = MessagePublisherImpl { failingProducer }

            shouldThrow<RuntimeException> {
                publisher.publish(
                    publishedMessage(),
                )
            }.message shouldBe exception.message
        }

        test("propagates timeout when producer does not respond") {
            val publisher =
                MessagePublisherImpl(
                    producerFactory = {
                        MockProducer(false, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
                    },
                    timeoutMillis = 100.milliseconds,
                )
            shouldThrow<TimeoutCancellationException> {
                publisher.publish(publishedMessage())
            }
        }
    })

private fun publishedMessage(
    topic: String = "my-test-topic",
    id: String = "1",
    value: String = "value",
) = PublishedMessage(
    topic = topic,
    id = id,
    value = value,
)
