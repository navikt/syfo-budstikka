package no.nav.budstikka.testsupport

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class GrafanaContainer(
    network: Network,
) : AutoCloseable {
    private val provisioning = projectRoot().resolve("grafana/provisioning").toAbsolutePath().toString()

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
            ).withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

    init {
        container.start()
        provisionDashboard()
    }

    val url: String
        get() = "$baseUrl/d/$DASHBOARD_UID/syfo-budstikka"

    val dashboardApiUrl: String
        get() = "$baseUrl/apis/dashboard.grafana.app/v2/namespaces/default/dashboards/$DASHBOARD_UID"

    override fun close() {
        container.stop()
    }

    private fun provisionDashboard() {
        val dashboard = projectRoot().resolve("grafana/dashboards/syfo-budstikka.json").toFile().readText()
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUrl/apis/dashboard.grafana.app/v2/namespaces/default/dashboards"))
                .header("Authorization", "Basic ${Base64.getEncoder().encodeToString("admin:admin".toByteArray())}")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"apiVersion":"dashboard.grafana.app/v2","kind":"Dashboard","metadata":{"name":"syfo-budstikka","uid":"$DASHBOARD_UID"},"spec":$dashboard}""",
                    ),
                ).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 201) {
            "Grafana dashboard provisioning failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
    }

    private val baseUrl: String
        get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    private companion object {
        const val DASHBOARD_UID = "syfo-budstikka"
        const val IMAGE = "grafana/grafana:13.1.2"
        const val PORT = 3000
    }
}
