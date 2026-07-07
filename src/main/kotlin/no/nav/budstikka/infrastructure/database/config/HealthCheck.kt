package no.nav.syfo.no.nav.budstikka.infrastructure.database.config

import no.nav.syfo.no.nav.budstikka.infrastructure.HealthCheck
import no.nav.syfo.no.nav.budstikka.infrastructure.HealthResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("no.nav.budstikka.infrastructure.database.config.HealthCheck")

fun databaseHealthCheck(dataSource: DataSource): HealthCheck =
    {
        try {
            dataSource.connection.use {
                HealthResult(
                    healthy = it.isValid(1),
                    message = "Database",
                )
            }
        } catch (e: Exception) {
            logger.error("Database health check failed", e)

            HealthResult(
                healthy = false,
                message = "Database unavailable",
            )
        }
    }
