package no.nav.budstikka.testsupport

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.di.DependencyRegistry
import no.nav.budstikka.configureApplication
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.Network
import java.util.Properties

/**
 * Delt ende-til-ende-substrat (B50/B51): starter Postgres + Kafka fra kode, booter HELE appen
 * (konsument + workers + Ktor) in-process mot containerne, og lar [overrides] bytte ekte adaptere
 * mot fakes via wiring-sømmen [configureApplication]. Samme substrat brukes av e2e-specene og av
 * det lokale løpet ([no.nav.budstikka.LocalApp]).
 *
 * Prod-grensen holder: alt her ligger i `src/test`, aldri i prod-jaren.
 */
class BudstikkaTestApp private constructor(
    private val postgres: PostgresTestFixture,
    private val kafka: KafkaTestContainer,
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val appConfig: ApplicationConfig,
) : AutoCloseable {
    /** Egen tilkobling for assertions/inspeksjon mot samme Postgres-container som appen bruker. */
    val database: Database
        get() = postgres.database

    val bootstrapServers: String
        get() = kafka.bootstrapServers

    /**
     * Delt Docker-nett når appen ble startet med `enableKafkaNetwork = true` (kun lokalt løp);
     * ellers null. Brukes til å plassere Kafka UI på samme nett som Kafka.
     */
    val network: Network?
        get() = kafka.network

    /** Intern bootstrap-adresse (`kafka:19092`) på det delte nettet; null uten nett-lytter. */
    val internalBootstrapServers: String?
        get() = kafka.internalBootstrapServers

    /** JDBC-URL til den kjørende Postgres-containeren — logges ved lokalt løp for live-inspeksjon (B51). */
    val jdbcUrl: String
        get() = postgres.jdbcUrl

    val formidlingTopic: String
        get() = appConfig.property("kafka.consumers.formidling.topic").getString()

    /** Publiserer en record til [topic] med valgfrie headere (typisk eventId, jf. B54). */
    fun produce(
        topic: String,
        key: String?,
        value: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val props =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            }
        KafkaProducer<String, String>(props).use { producer ->
            val record = ProducerRecord(topic, key, value)
            headers.forEach { (name, headerValue) -> record.headers().add(name, headerValue.toByteArray()) }
            producer.send(record).get()
        }
    }

    override fun close() {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        kafka.close()
        postgres.close()
    }

    companion object {
        /**
         * Starter containerne, booter appen med [overrides] og venter til serveren er oppe.
         * Kaller [AutoCloseable.close] (evt. via `use { }`) for å rive alt ned.
         *
         * Med [enableKafkaNetwork] = true får Kafka et delt Docker-nett + intern lytter, slik at
         * det lokale løpet kan koble Kafka UI på samme nett. E2e lar den stå av (default).
         */
        fun start(
            enableKafkaNetwork: Boolean = false,
            overrides: DependencyRegistry.() -> Unit = {},
        ): BudstikkaTestApp {
            val postgres = PostgresTestFixture()
            val kafka = KafkaTestContainer(enableNetworkListener = enableKafkaNetwork)
            try {
                val appConfig = testConfig(postgres, kafka.bootstrapServers)
                val server =
                    embeddedServer(
                        Netty,
                        environment = applicationEnvironment { config = appConfig },
                        configure = { connector { port = 0 } },
                        module = { configureApplication(overrides) },
                    )
                server.start(wait = false)
                return BudstikkaTestApp(postgres, kafka, server, appConfig)
            } catch (error: Throwable) {
                // Boot feilet etter at containerne startet — riv dem ned så vi ikke lekker Docker-ressurser.
                runCatching { kafka.close() }
                runCatching { postgres.close() }
                throw error
            }
        }

        /**
         * Bygger app-konfigen fra `application.conf` og peker DB + Kafka mot containerne. Setter
         * `ktor.di.conflictPolicy = OverridePrevious` (så [overrides] vinner) og tømmer
         * `ktor.application.modules` (vi laster modulen programmatisk, ikke fra konfigen).
         */
        private fun testConfig(
            postgres: PostgresTestFixture,
            bootstrapServers: String,
        ): ApplicationConfig {
            val host = postgres.postgres.host
            val port = postgres.postgres.firstMappedPort
            val overrides =
                ConfigFactory.parseMap(
                    mapOf(
                        "database.host" to host,
                        "database.port" to port.toString(),
                        "database.name" to postgres.postgres.databaseName,
                        "database.username" to postgres.username,
                        "database.password" to postgres.password,
                        "database.url" to "postgresql://$host:$port/${postgres.postgres.databaseName}",
                        "kafka.bootstrapServers" to bootstrapServers,
                        "kafka.consumers.formidling.enabled" to "true",
                        "ktor.di.conflictPolicy" to "OverridePrevious",
                        "ktor.application.modules" to emptyList<String>(),
                    ),
                )
            val merged = overrides.withFallback(ConfigFactory.parseResources("application.conf")).resolve()
            return HoconApplicationConfig(merged)
        }
    }
}
