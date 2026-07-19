package no.nav.budstikka.testsupport

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Testcontainers for Prometheus + Grafana (lokal utvikling). Gir ei visuell oversikt over * metrikker til appen utanfor NAIS Grafana Cloud. * * Prometheus scrapar appen på [appPort] via `/internal/metrics`. Grafana er provisionert med * Prometheus-datasource og dashboard-fila `syfo-budstikka.json`. * * Loki-panela i dashboardet viser "no data" lokalt (ingen Loki-container); dei fungerer i NAIS. */
class MonitoringContainers internal constructor(
    private val prometheus: GenericContainer<*>,
    private val grafana: GenericContainer<*>,
) : AutoCloseable {
    /** Host-nåbar URL til Grafana så lenge prosessen lever. */
    val grafanaUrl: String
        get() = "http://${grafana.host}:${grafana.getMappedPort(GRAFANA_PORT)}"

    init {
        prometheus.start()
        grafana.start()
    }

    override fun close() {
        grafana.stop()
        prometheus.stop()
    }

    companion object {
        private const val PROMETHEUS_IMAGE = "prom/prometheus:v3.4.2"
        private const val GRAFANA_IMAGE = "grafana/grafana:12.1.0"
        internal const val PROMETHEUS_PORT = 9090
        internal const val GRAFANA_PORT = 3000

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

        /** Går opp frå denne klassen si plassering til prosjektrota (der `build.gradle.kts` ligg). */
        fun projectRoot(): Path {
            val classDir =
                Path.of(
                    MonitoringContainers::class.java
                        .protectionDomain
                        .codeSource
                        .location
                        .toURI()
                        .path,
                )
            return generateSequence(classDir) { it.parent }
                .first {
                    it.resolve("build.gradle.kts").toFile().exists()
                }
        }
    }
}

/**
 * Startar Prometheus + Grafana og returnerer [MonitoringContainers] med URL-ar og * [AutoCloseable.close]-støtte. Tar inn [appPort] — porten Ktor-tenaren lyttar på. */
fun startMonitoring(appPort: Int): MonitoringContainers {
    val projectRoot = MonitoringContainers.projectRoot()
    val dockerHost = "host.docker.internal"
    val prometheusConfig = MonitoringContainers.prometheusConfig(appPort, dockerHost)

    val network = Network.newNetwork()

    val prometheus =
        GenericContainer(DockerImageName.parse("prom/prometheus:v3.4.2"))
            .withNetwork(network)
            .withNetworkAliases("prometheus")
            .withCopyFileToContainer(
                MountableFile.forHostPath(prometheusConfig.absolutePath, 0b110100100),
                "/tmp/prometheus.yml",
            ).withCommand("--config.file=/tmp/prometheus.yml")
            .withExposedPorts(MonitoringContainers.PROMETHEUS_PORT)
            .waitingFor(Wait.forHttp("/-/healthy").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

    val grafanaProvisioning = projectRoot.resolve("grafana/provisioning").toAbsolutePath().toString()
    val grafanaDashboard = projectRoot.resolve("grafana/dashboards/syfo-budstikka.json").toAbsolutePath().toString()

    val grafana =
        GenericContainer(DockerImageName.parse("grafana/grafana:12.1.0"))
            .withNetwork(network)
            .withEnv("GF_SECURITY_ADMIN_USER", "admin")
            .withEnv("GF_SECURITY_ADMIN_PASSWORD", "admin")
            .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true")
            .withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", "Viewer")
            .withFileSystemBind(
                "$grafanaProvisioning/datasources",
                "/etc/grafana/provisioning/datasources",
                BindMode.READ_ONLY,
            ).withFileSystemBind(
                "$grafanaProvisioning/dashboards",
                "/etc/grafana/provisioning/dashboards",
                BindMode.READ_ONLY,
            ).withFileSystemBind(
                grafanaDashboard,
                "/var/lib/grafana/dashboards/syfo-budstikka.json",
                BindMode.READ_ONLY,
            ).withExposedPorts(MonitoringContainers.GRAFANA_PORT)
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

    return MonitoringContainers(prometheus, grafana)
}
