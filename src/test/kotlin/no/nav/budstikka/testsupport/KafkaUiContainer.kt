package no.nav.budstikka.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Kafka UI (web) for det LOKALE løpet — gir en nettleserflate for å inspisere topics, meldinger,
 * konsumentgrupper og offsets mens appen kjører. Brukes KUN av [no.nav.budstikka.LocalApp], aldri
 * i e2e-testene (holder e2e raskt og uten UI-overhead).
 *
 * Ligger på det SAMME delte Docker-[network] som Kafka og når broker-en via den interne adressen
 * [kafkaBootstrapServers] (`kafka:19092`, jf. [KafkaTestContainer.internalBootstrapServers]). Selve
 * web-porten eksponeres som en Testcontainers-mappet (bridge) port slik at den er nåbar fra host —
 * host-nettverk fungerer ikke under podman-VM-en på Mac. Se [url] for den host-nåbare adressen.
 */
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

    /** Host-nåbar nettleser-URL til Kafka UI så lenge prosessen lever. */
    val url: String
        get() = "http://${container.host}:${container.getMappedPort(UI_PORT)}"

    override fun close() {
        container.stop()
    }

    private companion object {
        const val UI_PORT = 8080
    }
}
