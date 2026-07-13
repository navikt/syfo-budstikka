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

        suspend fun makePoison(
            eventId: UUID,
            attempt: Int,
        ) {
            fixture.database.transact {
                InboxMessageTable.update({ InboxMessageTable.eventId eq eventId }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[InboxMessageTable.attempt] = attempt
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

            val claimed = repository.claim(limit = 1, lease = lease, maxAttempts = 10)

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

            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)
            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(0)
        }

        test("claim reclaims a CLAIMED row after its lease has expired") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000004")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))

            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)
            expireLease(eventId)

            val reclaimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10)
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
            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)

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
            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)

            fixture.database.transact { repository.markFailedInTransaction(eventId, reason) } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "FAILED"
                row[InboxMessageTable.errorMessage] shouldBe reason
                row[InboxMessageTable.processedAt] shouldNotBe null
            }
        }

        test("claim fails a poison row that reached maxAttempts instead of reclaiming it") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000030")
            repository.saveBatch(listOf(eventId to """{"eventId":"$eventId"}"""))
            val maxAttempts = 3

            // Drive the row through maxAttempts claims without terminating it (simulates a
            // deterministic processing failure that always leaves the row CLAIMED).
            repeat(maxAttempts) {
                repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).shouldHaveSize(1)
                expireLease(eventId)
            }

            // The next poll must terminate the poison row instead of reclaiming it forever.
            repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).shouldHaveSize(0)

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "FAILED"
                row[InboxMessageTable.attempt] shouldBe maxAttempts
                row[InboxMessageTable.nextAttemptTime] shouldBe null
                row[InboxMessageTable.processedAt] shouldNotBe null
                row[InboxMessageTable.errorMessage] shouldNotBe null
            }
        }

        test("a poison row at the head of the queue does not block a healthy newer row") {
            val repository = InboxMessageRepositoryImpl(fixture.database)
            val poisonEventId = UUID.fromString("00000000-0000-0000-0000-000000000040")
            val healthyEventId = UUID.fromString("00000000-0000-0000-0000-000000000041")
            // Poison saved first, so it sorts to the head of the queue (receivedAt ASC).
            repository.saveBatch(listOf(poisonEventId to """{"eventId":"$poisonEventId"}"""))
            repository.saveBatch(listOf(healthyEventId to """{"eventId":"$healthyEventId"}"""))
            makePoison(poisonEventId, attempt = 3)

            val claimed = repository.claim(limit = 1, lease = lease, maxAttempts = 3)

            claimed.map { it.eventId } shouldBe listOf(healthyEventId)
            fixture.database.transact {
                val poison = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq poisonEventId }.single()
                poison[InboxMessageTable.state] shouldBe "FAILED"
            }
        }
    })
