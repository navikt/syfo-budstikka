package no.nav.budstikka.infrastructure.kafka.producer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaMessagePublisherTest :
    FunSpec({
        test("publishes the topic, key and value to the kafka sender") {
            val sender = RecordingKafkaRecordSender()
            val publisher = KafkaMessagePublisher(sender)

            runBlocking {
                publisher.publish(
                    PublishedMessage(
                        topic = "min-side.aapen-microfrontend-v1",
                        id = "12345678901",
                        value = """{"type":"MikrofrontendAktiver"}""",
                    ),
                )
            }

            val record = sender.records.single()
            record.topic() shouldBe "min-side.aapen-microfrontend-v1"
            record.key() shouldBe "12345678901"
            record.value() shouldBe """{"type":"MikrofrontendAktiver"}"""
        }
    })

private class RecordingKafkaRecordSender : KafkaRecordSender {
    val records = mutableListOf<ProducerRecord<String, String>>()

    override suspend fun send(record: ProducerRecord<String, String>) {
        records += record
    }
}
