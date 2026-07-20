package no.nav.budstikka.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.nio.file.Files
import java.time.Duration

class PrometheusContainer(
    appPort: Int,
    network: Network,
) : AutoCloseable {
    private val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withNetwork(network)
            .withNetworkAliases("prometheus")
            .withCopyFileToContainer(
                MountableFile.forHostPath(prometheusConfig(appPort, DOCKER_HOST).absolutePath, 0b110100100),
                "/tmp/prometheus.yml",
            ).withCommand("--config.file=/tmp/prometheus.yml")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/-/healthy").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

    init {
        container.start()
    }

    val url: String
        get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    override fun close() {
        container.stop()
    }

    companion object {
        internal const val IMAGE = "prom/prometheus:v3.13.1"
        internal const val PORT = 9090
        private const val DOCKER_HOST = "host.docker.internal"

        fun prometheusConfig(
            appPort: Int,
            host: String,
        ): File {
            val template =
                Files.readString(
                    projectRoot().resolve("src/test/resources/prometheus-test.yml"),
                )
            return Files.createTempFile("prometheus", ".yml").toFile().apply {
                writeText(
                    template
                        .replace("{{APP_PORT}}", appPort.toString())
                        .replace("{{HOST}}", host),
                )
            }
        }
    }
}
