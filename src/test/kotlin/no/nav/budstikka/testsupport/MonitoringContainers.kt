package no.nav.budstikka.testsupport

import org.testcontainers.containers.Network

class MonitoringContainers(
    port: Int,
) : AutoCloseable {
    private val network = Network.newNetwork()
    private val prometheus = PrometheusContainer(port, network)
    private val grafana = GrafanaContainer(network)

    val grafanaUrl: String
        get() = grafana.url

    override fun close() {
        grafana.close()
        prometheus.close()
        network.close()
    }
}
