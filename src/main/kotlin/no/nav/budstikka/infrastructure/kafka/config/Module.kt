package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.plugins.di.DependencyRegistry
import no.nav.budstikka.infrastructure.LivenessCheck
import no.nav.budstikka.infrastructure.database.formidling.DeadLetterFormidlingRepository
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepository
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner
import no.nav.budstikka.infrastructure.kafka.consumer.MessageHandler
import no.nav.budstikka.infrastructure.kafka.consumer.InboxHandler
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisherImpl
import no.nav.budstikka.infrastructure.kafka.producer.MicrofrontendPublisher
import no.nav.budstikka.infrastructure.kafka.producer.microfrontendPublisher
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

fun DependencyRegistry.kafkaModule() {
    provide<InboxHandler> { InboxHandler(resolve<InboxFormidlingRepository>(), resolve<DeadLetterFormidlingRepository>()) }
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
        val enabledConsumers = kafkaConfig.consumers.filterValues { it.enabled }
        enabledConsumers.map { (name, consumerConfig) ->
            ConsumerRunner(
                consumerFactory = {
                    KafkaConsumer(
                        PropertiesFactory(kafkaConfig).consumer(
                            groupId = consumerConfig.groupId,
                            autoOffsetReset = consumerConfig.autoOffsetReset,
                            maxPollRecords = consumerConfig.maxPollRecords,
                            keyDeserializer = StringDeserializer::class.java,
                            valueDeserializer = StringDeserializer::class.java,
                        ),
                    )
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
    provide<LivenessCheck> {
        val runners = resolve<List<ConsumerRunner<*, *>>>()
        LivenessCheck { runners.all { it.isAlive() } }
    }
}

private const val MINSIDE_PRODUCER = "minside"

private suspend fun DependencyRegistry.handlerForConsumer(name: String): MessageHandler<String, String?> =
    when (name) {
        "formidling" -> resolve<InboxHandler>()
        else -> error("Unknown Kafka consumer: $name")
    }
