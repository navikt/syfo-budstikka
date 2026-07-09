package no.nav.budstikka.infrastructure.database

import java.sql.DriverManager
import java.sql.ResultSet

@Suppress("SqlSourceToSinkFlow")
fun assertRow(
    fixture: PostgresTestFixture,
    query: String,
    assertion: ResultSet.() -> Unit,
) {
    DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
        connection.prepareStatement(query).use { statement ->
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.assertion()
                check(!resultSet.next())
            }
        }
    }
}
