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
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days

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

        test("retention cannot delete a source CREATE while a dependent INACTIVATE is nonterminal") {
            val sourceId = support.delivery(support.clock.now() - 181.days, DeliveryState.SENT)
            val dependentId = support.delivery(support.clock.now(), DeliveryState.READY)
            support.fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq dependentId }) {
                    it[sourceCreateDeliveryId] = sourceId
                }
            }

            shouldThrow<Exception> {
                support.fixture.database.transact {
                    DeliveryTable.deleteWhere { DeliveryTable.id eq sourceId }
                }
            }

            support.fixture.database.transact {
                val dependent = DeliveryTable.selectAll().where { DeliveryTable.id eq dependentId }.single()
                dependent[DeliveryTable.sourceCreateDeliveryId] shouldBe sourceId
                dependent[DeliveryTable.state] shouldBe DeliveryState.READY.name
            }
        }

        test("retention skips a referenced source and continues with unrelated eligible rows") {
            val old = support.clock.now() - 181.days
            val sourceId = support.delivery(old, DeliveryState.SENT)
            val dependentId = support.delivery(support.clock.now(), DeliveryState.READY)
            val unrelatedDeliveryId = support.delivery(old, DeliveryState.SENT)
            val inboxId = support.inbox(support.clock.now() - 101.days)
            val deadLetterId = support.deadLetter(support.clock.now() - 101.days, offset = 1)
            support.fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq dependentId }) {
                    it[operation] = "INACTIVATE"
                    it[sourceCreateDeliveryId] = sourceId
                }
            }

            support.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 1),
                )

            support.fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.id eq sourceId }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq dependentId }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq unrelatedDeliveryId }.count() shouldBe 0
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq inboxId }.count() shouldBe 0
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq deadLetterId }.count() shouldBe 0
            }
        }

        test("retention deletes an eligible dependent before its older source on a later pass") {
            val sourceId = support.delivery(support.clock.now() - 182.days, DeliveryState.SENT)
            val dependentId = support.delivery(support.clock.now() - 181.days, DeliveryState.FAILED)
            support.fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq dependentId }) {
                    it[operation] = "INACTIVATE"
                    it[sourceCreateDeliveryId] = sourceId
                }
            }

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
