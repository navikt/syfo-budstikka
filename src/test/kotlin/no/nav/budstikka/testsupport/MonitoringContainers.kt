package no.nav.budstikka.testsupport

import org.testcontainers.containers.Network

/**
 * Testcontainers for Prometheus + Grafana (lokal utvikling). Gir ei visuell oversikt over * metrikker til appen utanfor NAIS Grafana Cloud. * * Prometheus scrapar appen på [appPort] via `/internal/metrics`. Grafana er provisionert med * Prometheus-datasource og dashboard-fila `syfo-budstikka.json`. * * Loki-panela i dashboardet viser "no data" lokalt (ingen Loki-container); dei fungerer i NAIS. */
class MonitoringContainers(
    port: Int,
) : AutoCloseable {
    private val network = Network.newNetwork()
    private val prometheus = PrometheusContainer(port, network)
    private val grafana = GrafanaContainer(network)

    /** Host-nåbar URL til Grafana så lenge prosessen lever. */
    val grafanaUrl: String
        get() = grafana.url

    override fun close() {
        grafana.close()
        prometheus.close()
        network.close()
    }
}
