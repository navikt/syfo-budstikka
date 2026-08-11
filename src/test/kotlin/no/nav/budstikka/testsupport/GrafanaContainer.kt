package no.nav.budstikka.testsupport

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class GrafanaContainer(
    network: Network,
) : AutoCloseable {
    private val provisioning = projectRoot().resolve("grafana/provisioning").toAbsolutePath().toString()
    private val dashboard = projectRoot().resolve("grafana/dashboards/syfo-budstikka.json").toAbsolutePath().toString()

    private val container =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withNetwork(network)
            .withEnv("GF_SECURITY_ADMIN_USER", "admin")
            .withEnv("GF_SECURITY_ADMIN_PASSWORD", "admin")
            .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true")
            .withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", "Viewer")
            .withFileSystemBind(
                "$provisioning/datasources",
                "/etc/grafana/provisioning/datasources",
                BindMode.READ_ONLY,
            ).withFileSystemBind(
                "$provisioning/dashboards",
                "/etc/grafana/provisioning/dashboards",
                BindMode.READ_ONLY,
            ).withFileSystemBind(
                dashboard,
                "/var/lib/grafana/dashboards/syfo-budstikka.json",
                BindMode.READ_ONLY,
            ).withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

    init {
        container.start()
    }

    val url: String
        get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    override fun close() {
        container.stop()
    }

    private companion object {
        const val IMAGE = "grafana/grafana:13.0.3"
        const val PORT = 3000
    }
}
