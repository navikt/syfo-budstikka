package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.RoundRobinPartitioner
import org.apache.kafka.common.serialization.StringSerializer

class KafkaMessagePublisherTest :
    FunSpec({
        test("publishes the topic, key and value to Kafka") {
            val producer = MockProducer(true, RoundRobinPartitioner(), StringSerializer(), StringSerializer())
            val publisher = KafkaMessagePublisher(producer)

            runBlocking {
                publisher.publish(
                    PublishedMessage(
                        topic = "min-side.aapen-microfrontend-v1",
                        id = "12345678901",
                        value = """{"type":"MikrofrontendAktiver"}""",
                    ),
                )
            }

            val record = producer.history().single()
            record.topic() shouldBe "min-side.aapen-microfrontend-v1"
            record.key() shouldBe "12345678901"
            record.value() shouldBe """{"type":"MikrofrontendAktiver"}"""
        }
    })
