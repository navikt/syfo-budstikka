package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.postgresql.util.PSQLException
import java.sql.DriverManager

class NextAttemptTimeConstraintIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val waitConstraint = "inbox_message_wait_next_attempt_time_check"

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        test("inserting WAIT without next_attempt_time is rejected") {
            shouldThrow<PSQLException> {
                fixture.executeUpdate(
                    """
                    INSERT INTO inbox_message (reference, content, state)
                    VALUES ('constraint-test', '{}'::jsonb, 'WAIT')
                    """.trimIndent(),
                )
            }.serverErrorMessage?.constraint shouldBe waitConstraint
        }

        test("updating a row to WAIT without next_attempt_time is rejected") {
            fixture.executeUpdate(
                """
                INSERT INTO inbox_message (reference, content)
                VALUES ('constraint-test', '{}'::jsonb)
                """.trimIndent(),
            )

            shouldThrow<PSQLException> {
                fixture.executeUpdate("UPDATE inbox_message SET state = 'WAIT' WHERE reference = 'constraint-test'")
            }.serverErrorMessage?.constraint shouldBe waitConstraint
        }

        // Waking a valid WAIT via claim is covered by InboxDispatchRepositoryIntegrationTest:
        // "claim reclaims a WAIT row after next_attempt_time without consuming the attempt budget".
        test("inserting WAIT with next_attempt_time succeeds") {
            fixture.executeUpdate(
                """
                INSERT INTO inbox_message (reference, content, state, next_attempt_time)
                VALUES ('constraint-test', '{}'::jsonb, 'WAIT', CURRENT_TIMESTAMP + INTERVAL '1 minute')
                """.trimIndent(),
            ) shouldBe 1
        }

        listOf("inbox_message", "delivery").forEach { table ->
            val constraint = "${table}_claimed_next_attempt_time_check"

            test("$table rejects inserting CLAIMED without next_attempt_time") {
                shouldThrow<PSQLException> {
                    fixture.executeUpdate(table.claimedInsert(withNextAttemptTime = false))
                }.serverErrorMessage?.constraint shouldBe constraint
            }

            test("$table rejects updating to CLAIMED without next_attempt_time") {
                fixture.executeUpdate(table.unclaimedInsert()) shouldBe 1

                shouldThrow<PSQLException> {
                    fixture.executeUpdate(
                        "UPDATE $table SET state = 'CLAIMED', claim_token = gen_random_uuid() WHERE reference = 'constraint-test'",
                    )
                }.serverErrorMessage?.constraint shouldBe constraint
            }

            test("$table accepts CLAIMED with a claim_token and next_attempt_time") {
                fixture.executeUpdate(table.claimedInsert(withNextAttemptTime = true)) shouldBe 1
            }
        }
    })

private fun String.claimedInsert(withNextAttemptTime: Boolean): String {
    val columns = if (withNextAttemptTime) ", next_attempt_time" else ""
    val values = if (withNextAttemptTime) ", CURRENT_TIMESTAMP + INTERVAL '1 minute'" else ""
    return when (this) {
        "inbox_message" ->
            """
            INSERT INTO inbox_message (reference, content, state, claim_token$columns)
            VALUES ('constraint-test', '{}'::jsonb, 'CLAIMED', gen_random_uuid()$values)
            """.trimIndent()
        "delivery" ->
            """
            INSERT INTO delivery (reference, operation, channel, recipient_type, recipient_id, payload, state, claim_token$columns)
            VALUES ('constraint-test', 'CREATE', 'MICROFRONTEND', 'PERSON', 'recipient', '{}'::jsonb, 'CLAIMED', gen_random_uuid()$values)
            """.trimIndent()
        else -> error("Unexpected table: $this")
    }
}

private fun String.unclaimedInsert(): String =
    when (this) {
        "inbox_message" -> "INSERT INTO inbox_message (reference, content) VALUES ('constraint-test', '{}'::jsonb)"
        "delivery" ->
            """
            INSERT INTO delivery (reference, operation, channel, recipient_type, recipient_id, payload)
            VALUES ('constraint-test', 'CREATE', 'MICROFRONTEND', 'PERSON', 'recipient', '{}'::jsonb)
            """.trimIndent()
        else -> error("Unexpected table: $this")
    }

private fun PostgresTestFixture.executeUpdate(sql: String): Int =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeUpdate()
        }
    }
