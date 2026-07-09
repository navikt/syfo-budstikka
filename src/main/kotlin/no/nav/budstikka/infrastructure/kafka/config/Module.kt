package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.plugins.di.DependencyRegistry
import no.nav.budstikka.infrastructure.LivenessCheck
import no.nav.budstikka.infrastructure.database.formidling.DeadLetterFormidlingRepository
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepository
import no.nav.budstikka.infrastructure.kafka.formidling.InboxHandler
import no.nav.budstikka.infrastructure.kafka.producer.KafkaMessagePublisher
import no.nav.budstikka.infrastructure.kafka.producer.MessagePublisher
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
        KafkaMessagePublisher(resolve())
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

private suspend fun DependencyRegistry.handlerForConsumer(name: String): MessageHandler<String, String?> =
    when (name) {
        "formidling" -> resolve<InboxHandler>()
        else -> error("Unknown Kafka consumer: $name")
    }
