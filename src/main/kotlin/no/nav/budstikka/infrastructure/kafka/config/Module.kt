package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.plugins.di.DependencyRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.infrastructure.config.PlatformConfig
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.kafka.consumer.BatchMessageHandler
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner
import no.nav.budstikka.infrastructure.kafka.consumer.InboxMessageHandler
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisherImpl
import no.nav.budstikka.infrastructure.kafka.producer.microfrontendPublisher
import no.nav.budstikka.infrastructure.kafka.producer.minSideBrukervarselPublisher
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.atomic.AtomicReference

fun DependencyRegistry.kafkaModule() {
    provide<InboxMessageHandler> { InboxMessageHandler(resolve<InboxMessageRepository>(), resolve<DeadLetterMessageRepository>()) }
    provide<MessagePublisher> {
        val kafkaConfig = resolve<KafkaConfig>()
        MessagePublisherImpl {
            KafkaProducer(
                PropertiesFactory(kafkaConfig).producer(
                    keySerializer = StringSerializer::class.java,
                    valueSerializer = StringSerializer::class.java,
                ),
            )
        }
    }.cleanup(MessagePublisher::close)
    provide<MicrofrontendPublisher> {
        val topic =
            resolve<KafkaConfig>().producers[ProducerNames.MINSIDE_MICROFRONTEND]?.topic
                ?: error("Missing Kafka producer config: ${ProducerNames.MINSIDE_MICROFRONTEND}")
        microfrontendPublisher(topic = topic, messagePublisher = resolve(), platformConfig = resolve<PlatformConfig>())
    }
    provide<MinSideBrukervarselPublisher> {
        val kafkaConfig = resolve<KafkaConfig>()
        val topic =
            kafkaConfig.producers[ProducerNames.MINSIDE_BRUKERVARSEL]?.topic
                ?: error("Missing Kafka producer config: ${ProducerNames.MINSIDE_BRUKERVARSEL}")
        minSideBrukervarselPublisher(
            topic = topic,
            messagePublisher = resolve(),
            platformConfig = resolve<PlatformConfig>(),
        )
    }
    provide<List<ConsumerRunner<*, *>>> {
        val kafkaConfig = resolve<KafkaConfig>()
        val registry = resolve<PrometheusMeterRegistry>()
        val enabledConsumers = kafkaConfig.consumers.filterValues { it.enabled }
        enabledConsumers.map { (name, consumerConfig) ->
            // Close previous client metrics before rebinding on consumer restart.
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

object ProducerNames {
    const val MINSIDE_MICROFRONTEND = "minside-microfrontend"
    const val MINSIDE_BRUKERVARSEL = "minside-brukervarsel"
}

private suspend fun DependencyRegistry.handlerForConsumer(name: String): BatchMessageHandler<String, String?> =
    when (name) {
        "budstikka" -> resolve<InboxMessageHandler>()
        else -> error("Unknown Kafka consumer: $name")
    }
