package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class InboxDispatchRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

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
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.randomUUID()

            repository.saveBatch(listOf(inboxMessage(eventId)))
            repository.saveBatch(listOf(inboxMessage(eventId)))

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.count() shouldBe 1
            }
        }

        test("saveBatch writes rows in one call and ignores duplicates on event_id") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000020")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000021")
            repository.saveBatch(
                listOf(
                    inboxMessage(eventId1),
                    inboxMessage(eventId2),
                    inboxMessage(eventId1),
                ),
            )
            fixture.database.transact {
                InboxMessageTable.selectAll().count() shouldBe 2
            }
        }

        test("claim reads received rows in order, respects the limit and marks them CLAIMED") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
            repository.saveBatch(listOf(inboxMessage(eventId1)))
            repository.saveBatch(listOf(inboxMessage(eventId2)))

            val claimed = repository.claim(limit = 1, lease = lease, maxAttempts = 10)

            claimed.shouldHaveSize(1)
            claimed.single().message.eventId shouldBe eventId1
            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId1 }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.claimToken] shouldBe claimed.single().claimToken
                // Claiming reserves the row but does not spend a processing attempt (#157).
                row[InboxMessageTable.attempt] shouldBe 0
                row[InboxMessageTable.nextAttemptTime] shouldNotBe null
            }
        }

        test("claim skips a CLAIMED row while its lease is still valid") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000003")
            repository.saveBatch(listOf(inboxMessage(eventId)))

            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)
            repository.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(0)
        }

        test("one claim batch shares a token across its rows") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            repository.saveBatch(listOf(inboxMessage(UUID.randomUUID()), inboxMessage(UUID.randomUUID())))

            val claimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10)

            claimed.shouldHaveSize(2)
            claimed.map { it.claimToken }.distinct().shouldHaveSize(1)
            fixture.database.transact {
                InboxMessageTable.selectAll().map { it[InboxMessageTable.claimToken] }.distinct() shouldBe
                    listOf(claimed.first().claimToken)
            }
        }

        test("a reclaimed claim rejects every stale transition while the new token wins") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.randomUUID()
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val stale = repository.claim(1, lease, 10).single().claimToken
            expireLease(eventId)
            val current = repository.claim(1, lease, 10).single().claimToken
            current shouldNotBe stale

            repository.beginAttempt(eventId, stale, 10) shouldBe false
            fixture.database.transact {
                repository.markProcessedInTransaction(eventId, stale) shouldBe false
                repository.markDroppedInTransaction(eventId, stale, "stale") shouldBe false
                repository.markFailedInTransaction(eventId, stale, "stale") shouldBe false
                repository.markOutsideSendingWindowInTransaction(
                    eventId,
                    stale,
                    "stale",
                    Clock.System.now(),
                ) shouldBe false
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.claimToken] shouldBe current
                row[InboxMessageTable.attempt] shouldBe 0
            }
            repository.beginAttempt(eventId, current, 10) shouldBe true
            fixture.database.transact {
                repository.markDroppedInTransaction(eventId, current, "current") shouldBe true
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "DROPPED"
                row[InboxMessageTable.claimToken] shouldBe null
            }
        }

        test("claim reclaims a CLAIMED row after its lease has expired") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000004")
            repository.saveBatch(listOf(inboxMessage(eventId)))

            val firstToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken
            expireLease(eventId)

            val reclaimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10)
            reclaimed.shouldHaveSize(1)
            reclaimed.single().message.eventId shouldBe eventId
            reclaimed.single().claimToken shouldNotBe firstToken
            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                // Reclaiming an expired lease does not spend an attempt either (#157).
                row[InboxMessageTable.attempt] shouldBe 0
                row[InboxMessageTable.claimToken] shouldBe reclaimed.single().claimToken
            }
        }

        test("beginAttempt spends one attempt and refuses once the budget is gone") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000005")
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken

            repository.beginAttempt(eventId, claimToken, maxAttempts = 2) shouldBe true
            repository.beginAttempt(eventId, claimToken, maxAttempts = 2) shouldBe true
            repository.beginAttempt(eventId, claimToken, maxAttempts = 2) shouldBe false

            fixture.database.transact {
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq eventId }
                    .single()[InboxMessageTable.attempt] shouldBe 2
            }
        }

        test("beginAttempt refuses a row that is no longer CLAIMED") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000006")
            repository.saveBatch(listOf(inboxMessage(eventId)))

            repository.beginAttempt(eventId, UUID.randomUUID(), maxAttempts = 10) shouldBe false
        }

        test("markProcessedInTransaction transitions a CLAIMED row to PROCESSED") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken

            fixture.database.transact { repository.markProcessedInTransaction(eventId, claimToken) } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "PROCESSED"
                row[InboxMessageTable.claimToken] shouldBe null
                row[InboxMessageTable.errorMessage] shouldBe null
                row[InboxMessageTable.processedAt] shouldNotBe null
            }
        }

        test("markProcessedInTransaction is a no-op on a row that is not CLAIMED") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000012")
            repository.saveBatch(listOf(inboxMessage(eventId)))

            fixture.database.transact { repository.markProcessedInTransaction(eventId, UUID.randomUUID()) } shouldBe false
        }

        test("markFailedInTransaction transitions a CLAIMED row to FAILED with reason") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000011")
            val reason = "Invalid dispatch payload"
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken

            fixture.database.transact { repository.markFailedInTransaction(eventId, claimToken, reason) } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "FAILED"
                row[InboxMessageTable.claimToken] shouldBe null
                row[InboxMessageTable.errorMessage] shouldBe reason
                row[InboxMessageTable.processedAt] shouldNotBe null
            }
        }

        test("markOutsideSendingWindowInTransaction holds a CLAIMED row as WAIT with wait_reason, not error_message") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000050")
            val reason = "Closed Sunday"
            val nextRetry = Clock.System.now() + 30.minutes
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken
            repository.beginAttempt(eventId, claimToken, maxAttempts = 10) shouldBe true

            fixture.database.transact {
                repository.markOutsideSendingWindowInTransaction(eventId, claimToken, reason, nextRetry)
            } shouldBe true

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "WAIT"
                row[InboxMessageTable.claimToken] shouldBe null
                row[InboxMessageTable.waitReason] shouldBe reason
                row[InboxMessageTable.errorMessage] shouldBe null
                row[InboxMessageTable.nextAttemptTime] shouldNotBe null
                row[InboxMessageTable.processedAt] shouldBe null
                // Reaching a hold decision is a successful evaluation, so the attempt spent by
                // beginAttempt is handed back.
                row[InboxMessageTable.attempt] shouldBe 0
            }
        }

        test("claim reclaims a WAIT row after next_attempt_time without consuming the attempt budget") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000051")
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val firstToken = repository.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken
            repository.beginAttempt(eventId, firstToken, maxAttempts = 10) shouldBe true
            fixture.database.transact {
                repository.markOutsideSendingWindowInTransaction(
                    eventId,
                    firstToken,
                    "Closed Sunday",
                    Clock.System.now() + 30.minutes,
                )
            }
            // Sending window opens: next_attempt_time is now in the past.
            expireLease(eventId)

            val reclaimed = repository.claim(limit = 10, lease = lease, maxAttempts = 10)

            reclaimed.shouldHaveSize(1)
            reclaimed.single().message.eventId shouldBe eventId
            reclaimed.single().claimToken shouldNotBe firstToken
            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.claimToken] shouldBe reclaimed.single().claimToken
                // Waking from WAIT is a scheduled resume with a fresh budget: the hold handed the
                // spent attempt back, and neither the wake nor the claim consumes one.
                row[InboxMessageTable.attempt] shouldBe 0
                row[InboxMessageTable.waitReason] shouldBe "Closed Sunday"
            }
        }

        test("a repeatedly held WAIT row never poison-FAILs from waiting") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000052")
            val maxAttempts = 2
            repository.saveBatch(listOf(inboxMessage(eventId)))
            var claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).single().claimToken

            // Spend, hold and wake more times than maxAttempts; every hold must hand the spent
            // attempt back, so beginAttempt keeps authorising new processing starts.
            repeat(maxAttempts + 2) {
                repository.beginAttempt(eventId, claimToken, maxAttempts) shouldBe true
                fixture.database.transact {
                    repository.markOutsideSendingWindowInTransaction(
                        eventId,
                        claimToken,
                        "Closed Sunday",
                        Clock.System.now() + 30.minutes,
                    )
                }
                expireLease(eventId)
                claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).single().claimToken
            }

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.attempt] shouldBe 0
            }
        }

        test("claim fails a poison row that reached maxAttempts instead of reclaiming it") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000030")
            repository.saveBatch(listOf(inboxMessage(eventId)))
            val maxAttempts = 3

            // Drive the row through maxAttempts processing attempts without terminating it
            // (simulates a deterministic processing failure that always leaves the row CLAIMED).
            repeat(maxAttempts) {
                val claimToken = repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).single().claimToken
                repository.beginAttempt(eventId, claimToken, maxAttempts) shouldBe true
                expireLease(eventId)
            }

            // The next poll must terminate the poison row instead of reclaiming it forever.
            repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts).shouldHaveSize(0)

            fixture.database.transact {
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "FAILED"
                row[InboxMessageTable.claimToken] shouldBe null
                row[InboxMessageTable.attempt] shouldBe maxAttempts
                row[InboxMessageTable.nextAttemptTime] shouldBe null
                row[InboxMessageTable.processedAt] shouldNotBe null
                row[InboxMessageTable.errorMessage] shouldNotBe null
            }
        }

        test("a poison row at the head of the queue does not block a healthy newer row") {
            val repository = PostgresInboxMessageRepository(fixture.database)
            val poisonEventId = UUID.fromString("00000000-0000-0000-0000-000000000040")
            val healthyEventId = UUID.fromString("00000000-0000-0000-0000-000000000041")
            // Poison saved first, so it sorts to the head of the queue (receivedAt ASC).
            repository.saveBatch(listOf(inboxMessage(poisonEventId)))
            repository.saveBatch(listOf(inboxMessage(healthyEventId)))
            makePoison(poisonEventId, attempt = 3)

            val claimed = repository.claim(limit = 1, lease = lease, maxAttempts = 3)

            claimed.map { it.message.eventId } shouldBe listOf(healthyEventId)
            fixture.database.transact {
                val poison = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq poisonEventId }.single()
                poison[InboxMessageTable.state] shouldBe "FAILED"
                poison[InboxMessageTable.claimToken] shouldBe null
            }
        }
    })
