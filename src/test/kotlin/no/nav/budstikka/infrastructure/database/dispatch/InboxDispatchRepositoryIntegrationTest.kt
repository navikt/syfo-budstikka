package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import java.util.UUID

class InboxDispatchRepositoryIntegrationTest :
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

        test("save writes a row to inbox_formidling and deduplicates on event_id") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.randomUUID()
            val payload = """{"eventId":"$eventId"}"""

            repository.save(eventId, payload) shouldBe true
            repository.save(eventId, payload) shouldBe false
        }

        test("pollReceived reads received rows with limit") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val payload1 = """{"eventId":"$eventId1"}"""
            val payload2 = """{"eventId":"$eventId2"}"""

            repository.save(eventId1, payload1)
            repository.save(eventId2, payload2)

            val messages = repository.pollReceived(limit = 1)
            messages.shouldHaveSize(1)
            messages.single().eventId shouldBe eventId1
            messages.single().payload shouldBe payload1
        }
    })
