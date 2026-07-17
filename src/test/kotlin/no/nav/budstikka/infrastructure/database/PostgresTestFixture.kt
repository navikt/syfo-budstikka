package no.nav.budstikka.infrastructure.database

import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

class PostgresTestFixture : AutoCloseable {
    private val schemaName = "test_${UUID.randomUUID().toString().replace("-", "")}"
    val schema: String
        get() = schemaName

    val postgres: PostgreSQLContainer<*>
        get() = sharedPostgres

    private val schemaJdbcUrl: String
        get() = postgres.jdbcUrl.withCurrentSchema(schemaName)

    val database: Database by lazy {
        Database.connect(
            url = schemaJdbcUrl,
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
        get() = schemaJdbcUrl

    val username: String
        get() = postgres.username

    val password: String
        get() = postgres.password

    init {
        createSchema()
    }

    fun migrate() {
        Flyway
            .configure()
            .dataSource(schemaJdbcUrl, postgres.username, postgres.password)
            .locations("classpath:database.migration")
            .schemas(schemaName)
            .defaultSchema(schemaName)
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
        DriverManager.getConnection(postgres.jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""DROP SCHEMA IF EXISTS "$schemaName" CASCADE""")
            }
        }
    }

    private fun createSchema() {
        DriverManager.getConnection(postgres.jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""CREATE SCHEMA IF NOT EXISTS "$schemaName"""")
            }
        }
    }

    companion object {
        private val sharedPostgres: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("budstikka")
                .withUsername("budstikka")
                .withPassword("budstikka")
                .apply { start() }
        }
    }
}

private fun List<Table>.toConcatenateTableNameString(): String = this.joinToString(", ") { it.tableName }

private fun String.withCurrentSchema(schema: String): String =
    if (contains("?")) "$this&currentSchema=$schema" else "$this?currentSchema=$schema"
