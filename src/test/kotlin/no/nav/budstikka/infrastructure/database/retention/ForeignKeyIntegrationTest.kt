package no.nav.budstikka.infrastructure.database.retention

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
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
    })
