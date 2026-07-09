package no.nav.budstikka

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class PostgresTestFixture : AutoCloseable {
    private val postgres =
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

    fun reset() {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("TRUNCATE TABLE inbox_formidling, dead_letter_formidling RESTART IDENTITY CASCADE")
            }
        }
    }

    override fun close() {
        postgres.stop()
    }
}
