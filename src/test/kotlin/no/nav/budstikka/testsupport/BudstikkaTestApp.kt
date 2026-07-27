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
 * Shared end-to-end substrate (B50/B51): starts Postgres + Kafka in code, boots the entire app
 * (consumer + workers + Ktor) in process against the containers, and lets [overrides] replace real adapters
 * with fakes through the [configureApplication] wiring seam. The same substrate is used by e2e specs and the
 * local run ([no.nav.budstikka.LocalApp]).
 *
 * The production boundary holds: everything here is in `src/test`, never in the production JAR.
 */
class BudstikkaTestApp private constructor(
    private val postgres: PostgresTestFixture,
    private val kafka: KafkaTestContainer,
    internal val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val appConfig: ApplicationConfig,
    private val monitoring: MonitoringContainers? = null,
) : AutoCloseable {
    /** Separate connection for assertions/inspection against the same Postgres container as the app. */
    val database: Database
        get() = postgres.database

    val bootstrapServers: String
        get() = kafka.bootstrapServers

    /**
     * Shared Docker network when the app starts with `enableKafkaNetwork = true` (local run only);
     * otherwise null. Used to place Kafka UI on the same network as Kafka.
     */
    val network: Network?
        get() = kafka.network

    /** Internal bootstrap address (`kafka:19092`) on the shared network; null without a network listener. */
    val internalBootstrapServers: String?
        get() = kafka.internalBootstrapServers

    /** JDBC URL for the running Postgres container — logged during a local run for live inspection (B51). */
    val jdbcUrl: String
        get() = postgres.jdbcUrl

    val grafanaUrl: String?
        get() = monitoring?.grafanaUrl

    val budstikkaTopic: String
        get() = appConfig.property("kafka.consumers.budstikka.topic").getString()

    /** Publishes a record to [topic] with optional headers (typically eventId; see B54). */
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
         * Starts the containers, boots the app with [overrides], and waits until the server is ready.
         * Call [AutoCloseable] (for example via `use { }`) to tear everything down.
         *
         * With [enableKafkaNetwork] = true, Kafka receives a shared Docker network and an internal listener,
         * allowing the local run to connect Kafka UI on the same network. E2e leaves it disabled (the default).
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
                        // port = 0 picks a random free port (e2e runs in parallel without collisions);
                        // LocalApp passes a fixed port for stable Bruno/Grafana URLs.
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
                        // Points the booted app (boot migration + consumer + workers) at the same per-fixture
                        // schema read by assertions (PostgresTestFixture.schema), so the shared container can
                        // run multiple isolated runs in parallel.
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
