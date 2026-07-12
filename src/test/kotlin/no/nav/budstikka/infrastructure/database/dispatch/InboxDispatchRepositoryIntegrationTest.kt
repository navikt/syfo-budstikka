package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class InboxDispatchRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = Duration.ofMinutes(5)

        beforeSpec {
            fixture.migrate()
        }

        afterTest {
            fixture.reset()
        }

        afterSpec {
            fixture.close()
        }

        suspend fun expireLease(eventId: UUID) {
            fixture.database.transact {
                InboxMessageTable.update({ InboxMessageTable.eventId eq eventId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        test("saveBatch writes a row to inbox_message and deduplicates on event_id") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.randomUUID()
            val payload = """{"eventId":"$eventId"}"""

            repository.saveBatch(listOf(eventId to payload))
            repository.saveBatch(listOf(eventId to payload))

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.count() shouldBe 1
            }
        }

        test("saveBatch writes rows in one call and ignores duplicates on event_id") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000020")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000021")
            repository.saveBatch(
                listOf(
                    eventId1 to """{"eventId":"$eventId1"}""",
                    eventId2 to """{"eventId":"$eventId2"}""",
                    eventId1 to """{"eventId":"$eventId1"}""",
                ),
            )
            fixture.database.transact {
                InboxMessageTable.selectAll().count() shouldBe 2
            }
        }

        test("claim reads received rows in order, respects the limit and marks them CLAIMED") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
            repository.saveBatch(listOf(eventId1 to """{"eventId":"$eventId1"}"""))
            repository.saveBatch(listOf(eventId2 to """{"eventId":"$eventId2"}"""))

            val claimed = repository.claim(limit = 1, lease = lease)

            claimed.shouldHaveSize(1)
            claimed.single().eventId shouldBe eventId1
            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId1 }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.attempt] shouldBe 1
                row[InboxMessageTable.nextAttemptTime] shouldNotBe null
            }
        }

        test("claim skips a CLAIMED row while its lease is still valid") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000003")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))

            repository.claim(limit = 10, lease = lease).shouldHaveSize(1)
            repository.claim(limit = 10, lease = lease).shouldHaveSize(0)
        }

        test("claim reclaims a CLAIMED row after its lease has expired") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000004")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))

            repository.claim(limit = 10, lease = lease).shouldHaveSize(1)
            expireLease(eventId)

            val reclaimed = repository.claim(limit = 10, lease = lease)
            reclaimed.shouldHaveSize(1)
            reclaimed.single().eventId shouldBe eventId
            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.attempt] shouldBe 2
            }
        }

        test("markProcessedInTransaction transitions a CLAIMED row to PROCESSED") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))
            repository.claim(limit = 10, lease = lease).shouldHaveSize(1)

            fixture.database.transact { repository.markProcessedInTransaction(eventId) } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "PROCESSED"
                row[InboxMessageTable.errorMessage] shouldBe null
                row[InboxMessageTable.processedAt] shouldNotBe null
            }
        }

        test("markProcessedInTransaction is a no-op on a row that is not CLAIMED") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000012")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))

            fixture.database.transact { repository.markProcessedInTransaction(eventId) } shouldBe false
        }

        test("markFailedInTransaction transitions a CLAIMED row to FAILED with reason") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
            val reason = "Invalid dispatch payload"
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))
            repository.claim(limit = 10, lease = lease).shouldHaveSize(1)

            fixture.database.transact { repository.markFailedInTransaction(eventId, reason) } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "FAILED"
                row[InboxMessageTable.errorMessage] shouldBe reason
                row[InboxMessageTable.processedAt] shouldNotBe null
            }
        }
    })
