package no.nav.budstikka.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class KafkaUiContainer(
    network: Network,
    kafkaBootstrapServers: String,
) : AutoCloseable {
    private val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("provectuslabs/kafka-ui:v0.7.2"))
            .withNetwork(network)
            .withEnv("KAFKA_CLUSTERS_0_NAME", "local")
            .withEnv("KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS", kafkaBootstrapServers)
            .withEnv("DYNAMIC_CONFIG_ENABLED", "true")
            .withExposedPorts(UI_PORT)
            .waitingFor(
                Wait
                    .forHttp("/actuator/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)),
            )

    init {
        container.start()
    }

    val url: String
        get() = "http://${container.host}:${container.getMappedPort(UI_PORT)}"

    override fun close() {
        container.stop()
    }

    private companion object {
        const val UI_PORT = 8080
    }
}
