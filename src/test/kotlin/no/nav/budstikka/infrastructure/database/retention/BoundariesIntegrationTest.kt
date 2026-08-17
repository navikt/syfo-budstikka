package no.nav.budstikka.infrastructure.database.retention

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

class BoundariesIntegrationTest :
    FunSpec({
        val support = RepositoryTestSupport()

        beforeSpec { support.migrate() }
        afterTest { support.reset() }
        afterSpec { support.close() }

        test("deletes only rows strictly older than the retention boundaries and terminal deliveries") {
            val inboxCutoff = support.clock.now() - support.policy.inboxAndDeadLetterRetention
            val deliveryCutoff = support.clock.now() - support.policy.deliveryRetention
            val expiredInbox = support.inbox(inboxCutoff - 1.seconds)
            val boundaryInbox = support.inbox(inboxCutoff)
            val expiredDeadLetter = support.deadLetter(inboxCutoff - 1.seconds, offset = 1)
            val boundaryDeadLetter = support.deadLetter(inboxCutoff, offset = 2)
            val expiredSent = support.delivery(deliveryCutoff - 1.seconds, DeliveryState.SENT)
            val expiredFailed = support.delivery(deliveryCutoff - 1.seconds, DeliveryState.FAILED)
            val boundarySent = support.delivery(deliveryCutoff, DeliveryState.SENT)
            val expiredReady = support.delivery(deliveryCutoff - 1.seconds, DeliveryState.READY)
            val expiredClaimed = support.delivery(deliveryCutoff - 1.seconds, DeliveryState.CLAIMED)

            support.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 2),
                )

            support.fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq expiredInbox }.count() shouldBe 0
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq boundaryInbox }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq expiredDeadLetter }.count() shouldBe 0
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq boundaryDeadLetter }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredSent }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredFailed }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq boundarySent }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredReady }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredClaimed }.count() shouldBe 1
            }
        }
    })
