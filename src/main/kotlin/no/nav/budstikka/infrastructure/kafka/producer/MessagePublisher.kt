package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class PublishedMessage(
    val topic: String,
    val id: String,
    val value: String,
)

fun interface MessagePublisher : AutoCloseable {
    suspend fun publish(message: PublishedMessage)

    override fun close() = Unit
}

internal class MessagePublisherImpl(
    private val timeoutMillis: Duration = 3000.milliseconds,
    private val producerFactory: () -> Producer<String, String>,
) : MessagePublisher,
    AutoCloseable {
    private val producer = AtomicReference<Producer<String, String>?>()
    private val closed = AtomicBoolean(false)
    private val producerLock = Any()

    override suspend fun publish(message: PublishedMessage) {
        val activeProducer = producer()
        withTimeout(timeoutMillis)
        {
            suspendCancellableCoroutine<RecordMetadata> { cont ->
                activeProducer
                    .send(
                        ProducerRecord(
                            message.topic,
                            message.id,
                            message.value,
                        ),
                    ) { metadata, exception ->
                        if (exception != null) {
                            cont.resumeWithException(exception)
                        } else {
                            cont.resume(metadata)
                        }
                    }
            }
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
