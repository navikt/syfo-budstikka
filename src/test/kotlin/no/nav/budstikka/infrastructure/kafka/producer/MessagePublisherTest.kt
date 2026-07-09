package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.RoundRobinPartitioner
import org.apache.kafka.common.serialization.StringSerializer

class MessagePublisherTest :
    FunSpec({
        test("publishes the topic, key and value to Kafka") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = MessagePublisherImpl(producer)

            publisher.publish(
                PublishedMessage(
                    topic = "min-side.aapen-microfrontend-v1",
                    id = "12345678901",
                    value = """{"type":"MikrofrontendAktiver"}""",
                ),
            )

            with(producer.history().single()) {
                topic() shouldBe "min-side.aapen-microfrontend-v1"
                key() shouldBe "12345678901"
                value() shouldBe """{"type":"MikrofrontendAktiver"}"""
            }
        }
    })
