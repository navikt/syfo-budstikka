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
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.Network
import java.util.Properties
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Boots the full application against Postgres and Kafka containers. [overrides] can replace external
 * adapters with fakes for end-to-end tests and the local run.
 */
private const val POLL_ATTEMPTS = 5

class BudstikkaTestApp private constructor(
    private val postgres: PostgresTestFixture,
    private val kafka: KafkaTestContainer,
    internal val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val appConfig: ApplicationConfig,
    private val monitoring: MonitoringContainers? = null,
) : AutoCloseable {
    val database: Database
        get() = postgres.database

    val bootstrapServers: String
        get() = kafka.bootstrapServers

    val network: Network?
        get() = kafka.network

    val internalBootstrapServers: String?
        get() = kafka.internalBootstrapServers

    val jdbcUrl: String
        get() = postgres.jdbcUrl

    val grafanaUrl: String?
        get() = monitoring?.grafanaUrl

    val budstikkaTopic: String
        get() = appConfig.property("kafka.consumers.budstikka.topic").getString()

    val dineSykmeldteTopic: String
        get() = appConfig.property("kafka.producers.dinesykmeldte-hendelser.topic").getString()

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

    /** Reads [topic] from the beginning with a fresh consumer group on each call. */
    fun consumeRecords(
        topic: String,
        pollTimeout: Duration = 1.seconds,
    ): List<ConsumerRecord<String, String>> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-assert-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            }
        return KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            buildList {
                repeat(POLL_ATTEMPTS) {
                    consumer.poll(pollTimeout.toJavaDuration()).forEach { add(it) }
                }
            }
        }
    }

    override fun close() {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        kafka.close()
        postgres.close()
    }

    companion object {
        /**
         * Starts the containers and application. Closing the result tears down all resources.
         * [enableKafkaNetwork] adds the internal listener needed by the local Kafka UI.
         */
        fun start(
            kafka: KafkaTestContainer = KafkaTestContainer(),
            port: Int = 0,
            withMonitoring: ((appport: Int) -> MonitoringContainers)? = null,
            configOverrides: Map<String, String> = emptyMap(),
            overrides: DependencyRegistry.() -> Unit = {},
        ): BudstikkaTestApp {
            val postgres = PostgresTestFixture()
            try {
                val appConfig = testConfig(postgres, kafka.bootstrapServers, configOverrides)
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
            configOverrides: Map<String, String>,
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
                ConfigFactory
                    .parseMap(configOverrides)
                    .withFallback(containerValues)
                    .withFallback(ConfigFactory.parseResources("application-local.conf"))
                    .withFallback(ConfigFactory.parseResources("application.conf"))
                    .resolve()
            return HoconApplicationConfig(merged)
        }
    }
}
