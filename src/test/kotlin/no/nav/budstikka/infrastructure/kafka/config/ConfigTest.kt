package no.nav.budstikka.infrastructure.kafka.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

const val CONSUMER_NAME = "budstikka"
const val PRODUCER_NAME = "minside"

class ConfigTest :
    FunSpec({

        test("toKafkaConfig defaults consumer enabled to true") {
            val config = config().toKafkaConfig()

            config.bootstrapServers shouldBe "localhost:9092"
            config.consumers.getValue(CONSUMER_NAME).enabled shouldBe true
        }

        test("toKafkaConfig reads consumer enabled override") {
            val config = config(enabled = "false").toKafkaConfig()

            config.consumers.getValue(CONSUMER_NAME).enabled shouldBe false
        }

        test("toKafkaConfig validates consumer enabled value") {
            shouldThrow<IllegalStateException> {
                config(enabled = "maybe").toKafkaConfig()
            }.message shouldBe "Invalid kafka configuration: kafka.consumers.$CONSUMER_NAME.enabled must be true or false"
        }

        test("toKafkaConfig reads producer topic") {
            val config = config(producerTopic = "min-side.aapen-microfrontend-v1").toKafkaConfig()

            config.producers.getValue(PRODUCER_NAME).topic shouldBe "min-side.aapen-microfrontend-v1"
        }

        test("toKafkaConfig validates producer topic is set") {
            shouldThrow<IllegalStateException> {
                config(producerTopic = "").toKafkaConfig()
            }.message shouldBe "Invalid kafka configuration: kafka.producers.$PRODUCER_NAME.topic must be set"
        }
    })

private fun config(
    bootstrapServers: String = "localhost:9092",
    enabled: String = "true",
    topic: String = "teamsykmelding.syfo-sendt-sykmelding",
    groupId: String = "flaggskipet-sykmelding-v1",
    autoOffsetReset: String = "earliest",
    maxPollRecords: String = "100",
    producerTopic: String = "min-side.aapen-microfrontend-v1",
    truststorePath: String = "",
    keystorePath: String = "",
    credentialStorePassword: String = "",
): MapApplicationConfig =
    MapApplicationConfig(
        "kafka.bootstrapServers" to bootstrapServers,
        "kafka.consumers.$CONSUMER_NAME.enabled" to enabled,
        "kafka.consumers.$CONSUMER_NAME.topic" to topic,
        "kafka.consumers.$CONSUMER_NAME.groupId" to groupId,
        "kafka.consumers.$CONSUMER_NAME.autoOffsetReset" to autoOffsetReset,
        "kafka.consumers.$CONSUMER_NAME.maxPollRecords" to maxPollRecords,
        "kafka.producers.$PRODUCER_NAME.topic" to producerTopic,
        "kafka.truststorePath" to truststorePath,
        "kafka.keystorePath" to keystorePath,
        "kafka.credentialStorePassword" to credentialStorePassword,
    )
