package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.PostgresTestFixture
import java.sql.DriverManager
import java.util.UUID

class InboxFormidlingRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec {
            fixture.migrate()
        }

        afterTest {
            fixture.reset()
        }

        afterSpec {
            fixture.close()
        }

        test("save skriver rad i inbox_formidling og dedupliserer på event_id") {
            val repository = InboxFormidlingRepositoryImpl(fixture.database)
            val eventId = UUID.randomUUID()
            val payload = """{"eventId":"$eventId"}"""

            repository.save(eventId, payload) shouldBe true
            repository.save(eventId, payload) shouldBe false

            readInboxRow(fixture, eventId).shouldMatch(
                eventId = eventId,
                payload = payload,
                state = "RECEIVED",
                attempt = 0,
            )
        }
    })

private data class InboxRow(
    val eventId: UUID,
    val payload: String,
    val state: String,
    val attempt: Int,
)

private fun InboxRow.shouldMatch(
    eventId: UUID,
    payload: String,
    state: String,
    attempt: Int,
) {
    this.eventId shouldBe eventId
    this.payload shouldBe payload
    this.state shouldBe state
    this.attempt shouldBe attempt
}

private fun readInboxRow(
    fixture: PostgresTestFixture,
    eventId: UUID,
): InboxRow =
    DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT
                    ${InboxFormidlingTable.eventId.name},
                    ${InboxFormidlingTable.payload.name},
                    ${InboxFormidlingTable.state.name},
                    ${InboxFormidlingTable.dropReason.name},
                    ${InboxFormidlingTable.attempt.name},
                    ${InboxFormidlingTable.nextAttemptTime.name},
                    ${InboxFormidlingTable.receivedAt.name},
                    ${InboxFormidlingTable.processedAt.name},
                    ${InboxFormidlingTable.errorMessage.name}
                FROM inbox_formidling
                WHERE event_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    val row =
                        InboxRow(
                            eventId = resultSet.getObject(InboxFormidlingTable.eventId.name, UUID::class.java),
                            payload = resultSet.getString(InboxFormidlingTable.payload.name),
                            state = resultSet.getString(InboxFormidlingTable.state.name),
                            attempt = resultSet.getInt(InboxFormidlingTable.attempt.name),
                        )

                    check(!resultSet.next())
                    row
                }
            }
    }
