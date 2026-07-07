package no.nav.syfo.no.nav.budstikka.infrastructure.database.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

fun DependencyRegistry.databaseModule() {
    provide<HikariDataSource> { createDataSource(resolve()) }
        .cleanup(HikariDataSource::close)
    provide<Database> { Database.connect(resolve<DataSource>()) }
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
            maximumPoolSize = 3
            minimumIdle = 1
            connectionTimeout = 10_000
            idleTimeout = 300_000
            maxLifetime = 1_800_000
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        },
    )

fun DataSource.migrate() {
    Flyway
        .configure()
        .dataSource(this)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}
