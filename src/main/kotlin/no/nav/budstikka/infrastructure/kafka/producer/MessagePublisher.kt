package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.TimeUnit

data class PublishedMessage(
    val topic: String,
    val id: String,
    val value: String,
)

const val TIMEOUT_IN_SECONDS = 10L

fun interface MessagePublisher {
    suspend fun publish(message: PublishedMessage)
}

internal class MessagePublisherImpl(
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
                ).get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
        }
    }
}
