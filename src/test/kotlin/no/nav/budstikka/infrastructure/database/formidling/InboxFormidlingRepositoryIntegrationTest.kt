package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.assertRow
import java.sql.ResultSet
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

            assertRow(
                fixture = fixture,
                query =
                selectQuery,
                assertion = { shouldContainInboxFormidling(eventId, payload) },
            )
        }
    })

private fun ResultSet.shouldContainInboxFormidling(
    eventId: UUID?,
    payload: String,
) {
    getObject(InboxFormidlingTable.eventId.name, UUID::class.java) shouldBe eventId
    getString(InboxFormidlingTable.payload.name) shouldBe payload
    getString(InboxFormidlingTable.state.name) shouldBe "RECEIVED"
    getString(InboxFormidlingTable.dropReason.name) shouldBe null
    getInt(InboxFormidlingTable.attempt.name) shouldBe 0
    getString(InboxFormidlingTable.nextAttemptTime.name) shouldBe null
    getObject(InboxFormidlingTable.receivedAt.name) shouldNotBe null
    getString(InboxFormidlingTable.processedAt.name) shouldBe null
    getString(InboxFormidlingTable.errorMessage.name) shouldBe null
}

private val selectQuery: String =
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
    """.trimIndent()
