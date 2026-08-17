package no.nav.budstikka.infrastructure.database.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.seconds

class BatchingIntegrationTest :
    FunSpec({
        val support = RepositoryTestSupport()

        beforeSpec { support.migrate() }
        afterTest { support.reset() }
        afterSpec { support.close() }

        test("rejects batch sizes outside the defensive limit") {
            shouldThrow<IllegalArgumentException> {
                support.run(batchSize = 0)
            }.message shouldBe "batchSize must be between 1 and 100"

            shouldThrow<IllegalArgumentException> {
                support.run(batchSize = 101)
            }.message shouldBe "batchSize must be between 1 and 100"
        }

        test("deletes the oldest 100 candidates per table and continues on the next run") {
            val inboxCutoff = support.clock.now() - support.policy.inboxAndDeadLetterRetention
            val deliveryCutoff = support.clock.now() - support.policy.deliveryRetention
            val inboxIds = (1..101).map { support.inbox(inboxCutoff - (102 - it).seconds) }
            val deadLetterIds = (1..101).map { support.deadLetter(inboxCutoff - (102 - it).seconds, it.toLong()) }
            val deliveryIds = (1..101).map { support.delivery(deliveryCutoff - (102 - it).seconds, DeliveryState.SENT) }

            support.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 100, deadLetterMessages = 100, deliveries = 100),
                )
            support.rowCounts() shouldBe RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 1)

            support.fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq inboxIds.first() }.count() shouldBe 0
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq inboxIds.last() }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq deadLetterIds.first() }.count() shouldBe 0
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq deadLetterIds.last() }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq deliveryIds.first() }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq deliveryIds.last() }.count() shouldBe 1
            }

            support.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 1),
                )
            support.rowCounts() shouldBe RetentionCounts(inboxMessages = 0, deadLetterMessages = 0, deliveries = 0)
        }
    })
