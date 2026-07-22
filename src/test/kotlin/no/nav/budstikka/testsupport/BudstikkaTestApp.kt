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
import kotlinx.coroutines.runBlocking
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
    internal val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val appConfig: ApplicationConfig,
    private val monitoring: MonitoringContainers? = null,
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

    val grafanaUrl: String?
        get() = monitoring?.grafanaUrl

    val budstikkaTopic: String
        get() = appConfig.property("kafka.consumers.budstikka.topic").getString()

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
         * Kaller [AutoCloseable] (evt. via `use { }`) for å rive alt ned.
         *
         * Med [enableKafkaNetwork] = true får Kafka et delt Docker-nett + intern lytter, slik at
         * det lokale løpet kan koble Kafka UI på samme nett. E2e lar den stå av (default).
         */
        fun start(
            kafka: KafkaTestContainer = KafkaTestContainer(),
            port: Int = 0,
            withMonitoring: ((appport: Int) -> MonitoringContainers)? = null,
            overrides: DependencyRegistry.() -> Unit = {},
        ): BudstikkaTestApp {
            val postgres = PostgresTestFixture()
            try {
                val appConfig = testConfig(postgres, kafka.bootstrapServers)
                val server =
                    embeddedServer(
                        Netty,
                        environment = applicationEnvironment { config = appConfig },
                        // port = 0 gir en tilfeldig ledig port (e2e kjører parallelt uten kollisjon);
                        // LocalApp sender en fast port for stabil Bruno-/Grafana-URL.
                        configure = { connector { this.port = port } },
                        module = {
                            configureApplication(overrides)
                        },
                    )
                server.start(wait = false)

                val monitoringEnabled =
                    appConfig.propertyOrNull("monitoring.enabled")?.getString()?.toBooleanStrictOrNull() ?: true
                val monitoring =
                    withMonitoring
                        ?.takeIf { monitoringEnabled }
                        ?.let { factory ->
                            val appPort =
                                runBlocking {
                                    server.engine
                                        .resolvedConnectors()
                                        .first()
                                        .port
                                }
                            factory(appPort)
                        }

                return BudstikkaTestApp(postgres, kafka, server, appConfig, monitoring)
            } catch (error: Throwable) {
                runCatching { kafka.close() }
                runCatching { postgres.close() }
                throw error
            }
        }

        private fun testConfig(
            postgres: PostgresTestFixture,
            bootstrapServers: String,
        ): ApplicationConfig {
            val host = postgres.postgres.host
            val port = postgres.postgres.firstMappedPort
            val containerValues =
                ConfigFactory.parseMap(
                    mapOf(
                        "database.host" to host,
                        "database.port" to port.toString(),
                        "database.name" to postgres.postgres.databaseName,
                        "database.username" to postgres.username,
                        "database.password" to postgres.password,
                        // Peker den bootede appen (boot-migrering + konsument + workers) mot det
                        // samme per-fixture-schemaet som assertions leser fra (PostgresTestFixture.schema),
                        // slik at den delte containeren kan kjøre flere løp isolert/parallelt.
                        "database.url" to "postgresql://$host:$port/${postgres.postgres.databaseName}?currentSchema=${postgres.schema}",
                        "kafka.bootstrapServers" to bootstrapServers,
                    ),
                )
            val merged =
                containerValues
                    .withFallback(ConfigFactory.parseResources("application-local.conf"))
                    .withFallback(ConfigFactory.parseResources("application.conf"))
                    .resolve()
            return HoconApplicationConfig(merged)
        }
    }
}
