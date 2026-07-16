package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.RoundRobinPartitioner
import org.apache.kafka.common.serialization.StringSerializer

class MessagePublisherTest :
    FunSpec({
        test("publishes the topic, key and value to Kafka") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = MessagePublisherImpl { producer }

            publisher.publish(
                PublishedMessage(
                    topic = "min-side.aapen-microfrontend-v1",
                    id = "12345678901",
                    value = """{"type":"MicrofrontendEnable"}""",
                ),
            )

            with(producer.history().single()) {
                topic() shouldBe "min-side.aapen-microfrontend-v1"
                key() shouldBe "12345678901"
                value() shouldBe """{"type":"MicrofrontendEnable"}"""
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
                    PublishedMessage(
                        topic = "min-side.aapen-microfrontend-v1",
                        id = "12345678901",
                        value = """{"type":"MicrofrontendEnable"}""",
                    ),
                )
            }

            createdProducers shouldBe 0
        }

        test("closes producer during cleanup after it has been used") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = MessagePublisherImpl { producer }

            publisher.publish(
                PublishedMessage(
                    topic = "min-side.aapen-microfrontend-v1",
                    id = "12345678901",
                    value = """{"type":"MicrofrontendEnable"}""",
                ),
            )
            publisher.close()

            producer.closed() shouldBe true
        }
    })
