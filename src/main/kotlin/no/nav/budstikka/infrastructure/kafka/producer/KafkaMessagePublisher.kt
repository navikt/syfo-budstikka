package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

internal class KafkaMessagePublisher(
    private val kafkaRecordSender: KafkaRecordSender,
) : MessagePublisher {
    override suspend fun publish(message: PublishedMessage) {
        kafkaRecordSender.send(
            ProducerRecord(
                message.topic,
                message.id,
                message.value,
            ),
        )
    }
}

internal fun interface KafkaRecordSender {
    suspend fun send(record: ProducerRecord<String, String>)
}

internal class KafkaProducerRecordSender(
    private val producer: Producer<String, String>,
) : KafkaRecordSender {
    override suspend fun send(record: ProducerRecord<String, String>) {
        withContext(Dispatchers.IO) {
            producer.send(record).get()
        }
    }
}
