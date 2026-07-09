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

            val rows = readInboxRows(fixture, eventId)

            rows.size shouldBe 1

            val row = rows.single()
            row.eventId shouldBe eventId
            row.payload shouldBe payload
            row.state shouldBe "RECEIVED"
            row.attempt shouldBe 0
        }
    })

private data class InboxRow(
    val eventId: UUID,
    val payload: String,
    val state: String,
    val attempt: Int,
)

private fun readInboxRows(
    fixture: PostgresTestFixture,
    eventId: UUID,
): List<InboxRow> =
    DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT event_id, payload, state, attempt
                FROM inbox_formidling
                WHERE event_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                InboxRow(
                                    eventId = resultSet.getObject("event_id", UUID::class.java),
                                    payload = resultSet.getString("payload"),
                                    state = resultSet.getString("state"),
                                    attempt = resultSet.getInt("attempt"),
                                ),
                            )
                        }
                    }
                }
            }
    }
