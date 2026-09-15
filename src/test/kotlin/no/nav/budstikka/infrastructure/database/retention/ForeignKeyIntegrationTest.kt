package no.nav.budstikka.infrastructure.database.retention

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ForeignKeyIntegrationTest :
    FunSpec({
        val support = RepositoryTestSupport()

        beforeSpec { support.migrate() }
        afterTest { support.reset() }
        afterSpec { support.close() }

        test("deleting an expired inbox row sets linked delivery inbox_event_id to null") {
            val inboxEventId = support.inbox(support.clock.now() - 101.days)
            val deliveryId = support.delivery(support.clock.now(), DeliveryState.READY, inboxEventId)

            support.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                )

            support.fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.id eq deliveryId }
                    .single()[DeliveryTable.inboxEventId] shouldBe null
            }
        }

        test("retains an expired source CREATE until its expired dependent is removed") {
            val sourceId = support.delivery(support.clock.now() - 182.days, DeliveryState.SENT)
            val dependentId =
                support.inactivateDelivery(
                    sourceCreateDeliveryId = sourceId,
                    createdAt = support.clock.now() - 181.days,
                )

            support.run(batchSize = 1) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 0, deadLetterMessages = 0, deliveries = 1),
                )
            support.fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.id eq sourceId }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq dependentId }.count() shouldBe 0
            }

            support.run(batchSize = 1) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 0, deadLetterMessages = 0, deliveries = 1),
                )
            support.fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.id eq sourceId }.count() shouldBe 0
            }
        }
    })

private suspend fun RepositoryTestSupport.inactivateDelivery(
    sourceCreateDeliveryId: UUID,
    createdAt: Instant,
): UUID {
    val id = UUID.randomUUID()
    fixture.database.transact {
        DeliveryTable.insert {
            it[DeliveryTable.id] = id
            it[reference] = "retention-test"
            it[operation] = Operation.INACTIVATE.name
            it[channel] = "MICROFRONTEND"
            it[recipientType] = "PERSON"
            it[recipientId] = "recipient"
            it[payload] = inboxMessage(UUID.randomUUID()).content
            it[DeliveryTable.state] = DeliveryState.SENT.name
            it[DeliveryTable.createdAt] = createdAt
            it[DeliveryTable.sourceCreateDeliveryId] = sourceCreateDeliveryId
        }
    }
    return id
}
