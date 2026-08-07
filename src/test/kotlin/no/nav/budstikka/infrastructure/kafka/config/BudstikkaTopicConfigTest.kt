package no.nav.budstikka.infrastructure.kafka.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.HoconApplicationConfig
import no.nav.budstikka.contract.Budstikka

/**
 * The topic budstikka ships with must be the topic `:kontrakt` tells every Produsent to publish to. A
 * Produsent compiles against [Budstikka.TOPIC], so drift between the shipped default and the contract
 * would silently deliver nothing.
 *
 * This guards the checked-in default only. `KAFKA_BUDSTIKKA_TOPIC` remains a deliberate operational
 * override, and this test says nothing about what an environment may set at runtime.
 *
 * Unlike the other config tests, this one reads the real `application.conf` that ships in the jar rather
 * than a [io.ktor.server.config.MapApplicationConfig] fixture — a fixture could only restate the
 * expectation. It goes through the application's own parser ([toKafkaConfig]), so there is no second
 * interpretation of the file.
 */
class BudstikkaTopicConfigTest :
    FunSpec({
        test("the shipped configuration defaults to the topic the contract publishes to") {
            val consumers = shippedConfig().toKafkaConfig().consumers

            consumers.getValue(BUDSTIKKA_CONSUMER).topic shouldBe Budstikka.TOPIC
        }
    })

/** The consumer key `application.conf` declares for the Budstikka contract stream. */
private const val BUDSTIKKA_CONSUMER = "budstikka"

/**
 * Loads the packaged `application.conf` deterministically: the environment is switched off during
 * resolution, so the test asserts the shipped default and cannot be swayed by a `KAFKA_*` variable that
 * happens to be set on the machine running it. [databasePlaceholders] only satisfies the `database.url`
 * substitution, which is required and would otherwise fail resolution.
 */
private fun shippedConfig(): HoconApplicationConfig =
    HoconApplicationConfig(
        ConfigFactory
            .parseResources("application.conf")
            .withFallback(databasePlaceholders)
            .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false)),
    )

private val databasePlaceholders: Config =
    ConfigFactory.parseMap(
        mapOf(
            "database.host" to "localhost",
            "database.port" to "5432",
            "database.name" to "budstikka",
        ),
    )
