package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PublishedMessage(
    val topic: String,
    val id: String,
    val value: String,
)

const val TIMEOUT_IN_SECONDS = 10L

fun interface MessagePublisher : AutoCloseable {
    suspend fun publish(message: PublishedMessage)

    override fun close() = Unit
}

internal class MessagePublisherImpl(
    private val producerFactory: () -> Producer<String, String>,
) : MessagePublisher,
    AutoCloseable {
    private val producer = AtomicReference<Producer<String, String>?>()
    private val closed = AtomicBoolean(false)
    private val producerLock = Any()

    override suspend fun publish(message: PublishedMessage) {
        val activeProducer = producer()
        withContext(Dispatchers.IO) {
            activeProducer
                .send(
                    ProducerRecord(
                        message.topic,
                        message.id,
                        message.value,
                    ),
                ).get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        producer.getAndSet(null)?.close()
    }

    private fun producer(): Producer<String, String> {
        check(!closed.get()) { "MessagePublisher is closed" }
        return producer.get()
            ?: synchronized(producerLock) {
                check(!closed.get()) { "MessagePublisher is closed" }
                producer.get()
                    ?: producerFactory().also(producer::set)
            }
    }
}
