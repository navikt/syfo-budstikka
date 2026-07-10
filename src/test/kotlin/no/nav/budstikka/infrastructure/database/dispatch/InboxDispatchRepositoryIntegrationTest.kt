package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.core.spec.style.FunSpec
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
    })
