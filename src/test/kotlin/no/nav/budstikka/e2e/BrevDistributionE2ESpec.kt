package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.FakeDocumentDistributor
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Full BREV-spor for #21: Kafka → inbox → beslutning → delivery → BREV-handler → dokdist-port.
 * Dokdist og PDL byttes mot fakes via test-harnessen, men appen bootes med ekte DB, Kafka,
 * konsumenter og workers.
 */
@Tags("E2E")
class BrevDistributionE2ESpec :
    FunSpec({
        test("BrevCreate is distributed and delivery reaches SENT") {
            val documentDistributor = FakeDocumentDistributor()

            BudstikkaTestApp
                .start {
                    provide<DeathLookup> { FakeDeathLookup() }
                    provide<DocumentDistributor> { documentDistributor }
                }.use { app ->
                    val eventId = UUID.randomUUID()
                    val dispatch =
                        Dispatch(
                            eventId = eventId,
                            reference = "e2e-brev-ref-1",
                            content = BrevCreate(personIdentifier = TEST_SYKMELDT, journalpostId = "jp-e2e-1"),
                        )

                    app.produce(
                        topic = app.budstikkaTopic,
                        key = dispatch.content.partitionKey,
                        value = dispatchJson.encodeToString(dispatch),
                        headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
                    )

                    eventually(30.seconds) {
                        val state = app.deliveryStateFor(eventId)

                        state.inboxState shouldBe InboxMessageState.PROCESSED.name
                        state.deliveryState shouldBe DeliveryState.SENT.name
                        state.deliveryChannel shouldBe "BREV"
                        state.deliveryReference shouldBe dispatch.reference
                        documentDistributor.requests.map { it.journalpostId }.shouldContainExactly("jp-e2e-1")
                    }
                }
        }
    })

private data class BrevDeliveryState(
    val inboxState: String?,
    val deliveryState: String?,
    val deliveryChannel: String?,
    val deliveryReference: String?,
)

private suspend fun BudstikkaTestApp.deliveryStateFor(eventId: UUID): BrevDeliveryState =
    database.transact {
        val inboxState =
            InboxMessageTable
                .selectAll()
                .where { InboxMessageTable.eventId eq eventId }
                .singleOrNull()
                ?.get(InboxMessageTable.state)

        val delivery =
            DeliveryTable
                .selectAll()
                .where { DeliveryTable.inboxEventId eq eventId }
                .singleOrNull()

        BrevDeliveryState(
            inboxState = inboxState,
            deliveryState = delivery?.get(DeliveryTable.state),
            deliveryChannel = delivery?.get(DeliveryTable.channel),
            deliveryReference = delivery?.get(DeliveryTable.reference),
        )
    }
