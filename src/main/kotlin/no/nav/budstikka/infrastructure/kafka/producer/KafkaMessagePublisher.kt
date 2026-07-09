package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

internal class KafkaMessagePublisher(
    private val producer: Producer<String, String>,
) : MessagePublisher {
    override suspend fun publish(message: PublishedMessage) {
        withContext(Dispatchers.IO) {
            producer
                .send(
                    ProducerRecord(
                        message.topic,
                        message.id,
                        message.value,
                    ),
                ).get()
        }
    }
}
