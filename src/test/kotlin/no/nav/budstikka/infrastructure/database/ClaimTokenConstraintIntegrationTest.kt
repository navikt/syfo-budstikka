package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.ClaimToken
import org.postgresql.util.PSQLException
import java.sql.DriverManager
import java.sql.Types

class ClaimTokenConstraintIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        listOf("delivery", "inbox_message").forEach { table ->
            val unclaimedState = if (table == "delivery") "READY" else "RECEIVED"
            val constraint = "${table}_claim_token_state_check"

            test("$table rejects CLAIMED without a token") {
                shouldThrow<PSQLException> { fixture.insertRow(table, "CLAIMED", null) }
                    .serverErrorMessage
                    ?.constraint shouldBe constraint
            }

            test("$table rejects a token outside CLAIMED") {
                shouldThrow<PSQLException> { fixture.insertRow(table, unclaimedState, ClaimToken.generate()) }
                    .serverErrorMessage
                    ?.constraint shouldBe constraint
            }
        }
    })

private fun PostgresTestFixture.insertRow(
    table: String,
    state: String,
    token: ClaimToken?,
) {
    val sql =
        when (table) {
            "delivery" ->
                """
                INSERT INTO delivery (reference, operation, channel, recipient_type, recipient_id, payload, state, claim_token)
                VALUES ('constraint-test', 'CREATE', 'MICROFRONTEND', 'PERSON', 'recipient', '{}'::jsonb, ?, ?)
                """.trimIndent()
            "inbox_message" ->
                """
                INSERT INTO inbox_message (reference, content, state, claim_token)
                VALUES ('constraint-test', '{}'::jsonb, ?, ?)
                """.trimIndent()
            else -> error("Unexpected table: $table")
        }
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, state)
            if (token == null) {
                statement.setNull(2, Types.OTHER)
            } else {
                statement.setObject(2, token.value)
            }
            statement.executeUpdate()
        }
    }
}
