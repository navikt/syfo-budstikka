package no.nav.budstikka.infrastructure.kafka.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

data class ConsumerConfig(
    val enabled: Boolean = true,
    val topic: String,
    val groupId: String,
    val autoOffsetReset: String,
    val maxPollRecords: Int = DEFAULT_MAX_POLL_RECORDS,
) {
    companion object {
        const val DEFAULT_MAX_POLL_RECORDS = 100
    }
}

data class KafkaConfig(
    val bootstrapServers: String,
    val consumers: Map<String, ConsumerConfig>,
    val producers: Map<String, ProducerConfig> = emptyMap(),
    val security: SecurityConfig,
)

data class ProducerConfig(
    val topic: String,
)

private val supportedAutoOffsetResets = setOf("earliest", "latest", "none")
private val supportedEnabledValues = setOf("true", "false")
private val consumerPathPattern =
    Regex("""kafka\.consumers\.([^.]+)\.(enabled|topic|groupId|autoOffsetReset|maxPollRecords)""")
private val producerPathPattern =
    Regex("""kafka\.producers\.([^.]+)\.(topic)""")

fun ApplicationConfig.toKafkaConfig() =
    with(configFor("kafka")) {
        val bootstrapServers = this("bootstrapServers")
        val consumerNames = consumerNames()
        val consumers =
            consumerNames.associateWith { consumerName ->
                val read = configFor("kafka.consumers.$consumerName")
                ConsumerConfig(
                    enabled = read("enabled").lowercase() != "false",
                    topic = read("topic"),
                    groupId = read("groupId"),
                    autoOffsetReset = read("autoOffsetReset").lowercase(),
                    maxPollRecords =
                        read("maxPollRecords").toIntOrNull()?.takeIf { it > 0 }
                            ?: ConsumerConfig.DEFAULT_MAX_POLL_RECORDS,
                )
            }
        val truststorePath = this("truststorePath")
        val keystorePath = this("keystorePath")
        val credentialStorePassword = this("credentialStorePassword")

        val producerNames = producerNames()
        val producers =
            producerNames.associateWith { producerName ->
                val read = configFor("kafka.producers.$producerName")
                ProducerConfig(topic = read("topic"))
            }

        KafkaConfig(
            bootstrapServers = bootstrapServers,
            consumers = consumers,
            producers = producers,
            security =
                SecurityConfig.from(
                    truststorePath = truststorePath,
                    keystorePath = keystorePath,
                    credentialStorePassword = credentialStorePassword,
                ),
        ).validate {
            buildList {
                if (bootstrapServers.isBlank()) add("kafka.bootstrapServers must be set")
                if (consumerNames.isEmpty()) add("kafka.consumers must define at least one consumer")
                consumers.forEach { (consumerName, consumer) ->
                    val path = "kafka.consumers.$consumerName"
                    val enabled = configFor("kafka.consumers.$consumerName")("enabled").lowercase()
                    if (enabled.isNotBlank() && enabled !in supportedEnabledValues) {
                        add("$path.enabled must be true or false")
                    }
                    if (consumer.topic.isBlank()) add("$path.topic must be set")
                    if (consumer.groupId.isBlank()) add("$path.groupId must be set")
                    when {
                        consumer.autoOffsetReset.isBlank() -> {
                            add("$path.autoOffsetReset must be set")
                        }

                        consumer.autoOffsetReset !in supportedAutoOffsetResets -> {
                            add("$path.autoOffsetReset must be one of ${supportedAutoOffsetResets.joinToString(", ")}")
                        }
                    }
                    val maxPollRecords = configFor("kafka.consumers.$consumerName")("maxPollRecords")
                    if (maxPollRecords.isNotBlank()) {
                        val parsed = maxPollRecords.toIntOrNull()
                        if (parsed == null || parsed <= 0) {
                            add("$path.maxPollRecords must be a positive integer")
                        }
                    }
                }

                producers.forEach { (producerName, producer) ->
                    if (producer.topic.isBlank()) add("kafka.producers.$producerName.topic must be set")
                }

                val sslValues =
                    listOf(
                        "kafka.truststorePath" to truststorePath,
                        "kafka.keystorePath" to keystorePath,
                        "kafka.credentialStorePassword" to credentialStorePassword,
                    )
                val configuredSslValues = sslValues.filter { (_, value) -> value.isNotBlank() }
                if (configuredSslValues.isNotEmpty() && configuredSslValues.size != sslValues.size) {
                    add(
                        "kafka.truststorePath, kafka.keystorePath and " +
                            "kafka.credentialStorePassword must either all be set or all be blank",
                    )
                }
            }
        }
    }

private fun ApplicationConfig.consumerNames(): Set<String> =
    keys()
        .mapNotNull { key -> consumerPathPattern.matchEntire(key)?.groupValues?.get(1) }
        .toSortedSet()

private fun ApplicationConfig.producerNames(): Set<String> =
    keys()
        .mapNotNull { key -> producerPathPattern.matchEntire(key)?.groupValues?.get(1) }
        .toSortedSet()

sealed interface SecurityConfig {
    data object Plaintext : SecurityConfig

    data class Ssl(
        val truststorePath: String,
        val keystorePath: String,
        private val credentialStorePassword: String,
    ) : SecurityConfig {
        fun password(): String = credentialStorePassword

        override fun toString(): String =
            "Ssl(" +
                "truststorePath=$truststorePath, " +
                "keystorePath=$keystorePath, " +
                "credentialStorePassword=***)"
    }

    companion object {
        fun from(
            truststorePath: String,
            keystorePath: String,
            credentialStorePassword: String,
        ): SecurityConfig =
            if (truststorePath.isBlank() && keystorePath.isBlank() && credentialStorePassword.isBlank()) {
                Plaintext
            } else {
                Ssl(
                    truststorePath = truststorePath,
                    keystorePath = keystorePath,
                    credentialStorePassword = credentialStorePassword,
                )
            }
    }
}
