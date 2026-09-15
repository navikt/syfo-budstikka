package no.nav.budstikka.infrastructure.database.config

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.esyfo.observability.Event

internal object DatabaseHealthLogEvents {
    val healthCheckFailed =
        Event<Unit>(
            name = "database.health_check.failed",
            level = ApplicationLogLevel.ERROR,
            message = "Database health check failed",
            operation = "database.health_check",
            errorCode = "DATABASE_HEALTH_CHECK_FAILED",
        )
}
