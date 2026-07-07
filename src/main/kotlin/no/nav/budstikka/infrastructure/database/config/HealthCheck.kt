package no.nav.budstikka.infrastructure.database.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.budstikka.infrastructure.HealthCheck
import no.nav.budstikka.infrastructure.HealthResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger(DataSourceHealthCheck::class.java)

fun dataSourceHealthCheck(dataSource: DataSource): HealthCheck = DataSourceHealthCheck(dataSource)

private class DataSourceHealthCheck(
    private val dataSource: DataSource,
) : HealthCheck {
    override suspend fun check(): HealthResult =
        withContext(Dispatchers.IO) {
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
}
