package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.plugins.di.DependencyRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.kafka.consumer.BatchMessageHandler
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner
import no.nav.budstikka.infrastructure.kafka.consumer.InboxMessageHandler
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisherImpl
import no.nav.budstikka.infrastructure.kafka.producer.microfrontendPublisher
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.atomic.AtomicReference

fun DependencyRegistry.kafkaModule() {
    provide<InboxMessageHandler> { InboxMessageHandler(resolve<InboxMessageRepository>(), resolve<DeadLetterMessageRepository>()) }
    provide<KafkaProducer<String, String>> {
        KafkaProducer(
            PropertiesFactory(resolve<KafkaConfig>()).producer(
                keySerializer = StringSerializer::class.java,
                valueSerializer = StringSerializer::class.java,
            ),
        )
    }.cleanup { producer -> producer.close() }
    provide<MessagePublisher> {
        MessagePublisherImpl(resolve())
    }
    provide<MicrofrontendPublisher> {
        val topic =
            resolve<KafkaConfig>().producers[MINSIDE_PRODUCER]?.topic
                ?: error("Missing Kafka producer config: $MINSIDE_PRODUCER")
        microfrontendPublisher(topic = topic, messagePublisher = resolve())
    }
    provide<List<ConsumerRunner<*, *>>> {
        val kafkaConfig = resolve<KafkaConfig>()
        val registry = resolve<PrometheusMeterRegistry>()
        val enabledConsumers = kafkaConfig.consumers.filterValues { it.enabled }
        enabledConsumers.map { (name, consumerConfig) ->
            // Klientmetrikkene (bl.a. kafka_consumer_fetch_manager_records_lag_max, issue #41) bindes til
            // den aktive consumeren. ConsumerRunner bygger en fersk consumer ved hver transient restart,
            // så vi lukker det forrige bindet før vi binder det nye — ellers akkumuleres døde tidsserier
            // (ny auto-generert client.id per restart).
            val clientMetrics = AtomicReference<KafkaClientMetrics?>()
            ConsumerRunner(
                consumerFactory = {
                    KafkaConsumer<String, String?>(
                        PropertiesFactory(kafkaConfig).consumer(
                            groupId = consumerConfig.groupId,
                            autoOffsetReset = consumerConfig.autoOffsetReset,
                            maxPollRecords = consumerConfig.maxPollRecords,
                            keyDeserializer = StringDeserializer::class.java,
                            valueDeserializer = StringDeserializer::class.java,
                        ),
                    ).also { consumer ->
                        // Lukk-før-rebind: fjern forrige binder (og dens meters) FØR den nye bindes,
                        // så en ny binding aldri kolliderer med meters vi straks lukker.
                        clientMetrics.getAndSet(null)?.close()
                        clientMetrics.set(KafkaClientMetrics(consumer).apply { bindTo(registry) })
                    }
                },
                topics = listOf(consumerConfig.topic),
                handler = handlerForConsumer(name),
                coroutineName = "$name-kafka-consumer",
            )
        }
    }.cleanup { runners ->
        runners.forEach { runner ->
            runner.close()
        }
    }
}

private const val MINSIDE_PRODUCER = "minside"

private suspend fun DependencyRegistry.handlerForConsumer(name: String): BatchMessageHandler<String, String?> =
    when (name) {
        "budstikka" -> resolve<InboxMessageHandler>()
        else -> error("Unknown Kafka consumer: $name")
    }
