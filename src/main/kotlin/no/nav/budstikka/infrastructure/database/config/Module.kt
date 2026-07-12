package no.nav.budstikka.infrastructure.database.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.infrastructure.HealthCheck
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

fun DependencyRegistry.databaseModule() {
    provide<HikariDataSource> { createDataSource(resolve()) }
        .cleanup(HikariDataSource::close)
    provide<Database> { Database.connect(resolve<DataSource>()) }
    provide<TransactionRunner> { TransactionRunnerImpl(resolve()) }
    provide<HealthCheck> {
        dataSourceHealthCheck(resolve())
    }
    provide<InboxMessageRepository> { InboxMessageRepositoryImpl(resolve()) }
    provide<DeadLetterMessageRepository> { DeadLetterMessageRepositoryImpl(resolve()) }
    provide<DeliveryRepository> { DeliveryRepositoryImpl(resolve()) }
}

suspend fun <T> Database.transact(block: () -> T): T =
    withContext(Dispatchers.IO) {
        transaction(this@transact) { block() }
    }

fun createDataSource(databaseConfig: DatabaseConfig): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = databaseConfig.jdbcUrl
            username = databaseConfig.username
            password = databaseConfig.password
            maximumPoolSize = databaseConfig.maximumPoolSize
            minimumIdle = databaseConfig.minimumIdle
            connectionTimeout = databaseConfig.connectionTimeout
            idleTimeout = databaseConfig.idleTimeout
            maxLifetime = databaseConfig.maxLifetime
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        },
    )

fun DataSource.migrate() {
    Flyway
        .configure()
        .dataSource(this)
        .locations("classpath:database.migration")
        .load()
        .migrate()
}
