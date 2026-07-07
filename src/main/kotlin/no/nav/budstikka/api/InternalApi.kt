package no.nav.syfo.no.nav.budstikka.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.syfo.no.nav.budstikka.infrastructure.HealthCheck
import no.nav.syfo.no.nav.budstikka.infrastructure.checkHealth

private const val POD_HEALTH_PATH = "/internal/health"
const val POD_METRICS_PATH = "/internal/metrics"

fun Application.configureInternalApi() {
    val meterRegistry: PrometheusMeterRegistry by dependencies
    val healthChecks: List<HealthCheck> by dependencies

    routing {
        registerPodApi(healthChecks)
        registerMetricApi(meterRegistry)
    }
}

fun Routing.registerPodApi(healthChecks: List<HealthCheck>) {
    get("$POD_HEALTH_PATH/is_alive") {
        call.respondText("I'm alive! :)")
    }
    get("$POD_HEALTH_PATH/is_ready") {
        val result = checkHealth(healthChecks)
        if (result.healthy) {
            call.respondText(result.message)
        } else {
            call.respondText(
                result.message,
                status = HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}

fun Routing.registerMetricApi(meterRegistry: PrometheusMeterRegistry) {
    get(POD_METRICS_PATH) {
        call.respondText(meterRegistry.scrape())
    }
}
