package no.nav.budstikka.infrastructure.kafka.config

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Deserializer
import java.util.Properties

class PropertiesFactory(
    private val kafkaConfig: KafkaConfig,
) {
    fun consumer(
        groupId: String,
        autoOffsetReset: String,
        maxPollRecords: Int,
        keyDeserializer: Class<out Deserializer<*>>,
        valueDeserializer: Class<out Deserializer<*>>,
    ): Properties =
        Properties().apply {
            putAll(common())
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords.toString())
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false.toString())
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer.name)
        }

    fun common(): Properties =
        Properties().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServers)

            when (val security = kafkaConfig.security) {
                SecurityConfig.Plaintext -> {
                    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
                }

                is SecurityConfig.Ssl -> {
                    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                    put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PKCS12")
                    put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, security.truststorePath)
                    put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, security.password())
                    put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
                    put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, security.keystorePath)
                    put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, security.password())
                    put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, security.password())
                }
            }
        }
}
