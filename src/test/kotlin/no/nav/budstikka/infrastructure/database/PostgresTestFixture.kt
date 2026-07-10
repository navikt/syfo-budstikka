package no.nav.budstikka.infrastructure.database

import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class PostgresTestFixture : AutoCloseable {
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("budstikka")
            .withUsername("budstikka")
            .withPassword("budstikka")

    val database: Database by lazy {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
    }

    // Add new tables in this list to check exposed mapping vs. database state.
    val tables: List<Table> =
        listOf(
            InboxMessageTable,
            DeadLetterMessageTable,
            DeliveryTable,
        )

    val jdbcUrl: String
        get() = postgres.jdbcUrl

    val username: String
        get() = postgres.username

    val password: String
        get() = postgres.password

    init {
        postgres.start()
    }

    fun migrate() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:database.migration")
            .load()
            .migrate()
    }

    @Suppress("SqlSourceToSinkFlow")
    fun reset() {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("TRUNCATE TABLE ${tables.toConcatenateTableNameString()} RESTART IDENTITY CASCADE")
            }
        }
    }

    override fun close() {
        postgres.stop()
    }
}

private fun List<Table>.toConcatenateTableNameString(): String = this.joinToString(", ") { it.tableName }
