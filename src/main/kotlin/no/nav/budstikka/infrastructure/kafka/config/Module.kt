package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.plugins.di.DependencyRegistry
import no.nav.budstikka.infrastructure.kafka.formidling.InboxHandler
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer

fun DependencyRegistry.kafkaModule() {
    provide<MessageHandler<String, String?>> { InboxHandler() }
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
                handler = resolve<MessageHandler<String, String?>>(),
                coroutineName = "$name-kafka-consumer",
            )
        }
    }
}
