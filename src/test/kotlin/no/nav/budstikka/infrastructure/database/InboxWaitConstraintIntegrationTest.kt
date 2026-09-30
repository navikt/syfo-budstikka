package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.postgresql.util.PSQLException
import java.sql.DriverManager

class InboxWaitConstraintIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val constraint = "inbox_message_wait_next_attempt_time_check"

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
            }.serverErrorMessage?.constraint shouldBe constraint
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
            }.serverErrorMessage?.constraint shouldBe constraint
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
    })

private fun PostgresTestFixture.executeUpdate(sql: String): Int =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeUpdate()
        }
    }
