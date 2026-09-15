package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgivervarselChannelHandler
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.application.inbox.EffectuationResult
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.toDeliveryDraft
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.TransactionRunnerImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DeliveryExternalIdMigrationIntegrationTest :
    FunSpec({
        listOf(ArbeidsgiverMeldingstype.BESKJED, ArbeidsgiverMeldingstype.OPPGAVE).forEach { meldingstype ->
            test("V10 legacy $meldingstype closes the originally published inbox identity after V11") {
                PostgresTestFixture().use { fixture ->
                    fixture.migrateTo("10")
                    val create = LegacyCreate(content = syntheticCreate(meldingstype))
                    val publisher = FakeArbeidsgiverNotificationPublisher()
                    fixture.insertLegacyCreate(create)
                    fixture.createExternalId(create.id) shouldBe null
                    fixture.publishHistorically(create, publisher)

                    fixture.migrate()
                    val closeId = fixture.deriveClose(create)
                    fixture.worker(publisher).runOnce()

                    publisher.requests.single().eksternId shouldBe create.eventId.toString()
                    publisher.requests.single().meldingstype shouldBe meldingstype
                    publisher.closeRequests shouldBe
                        listOf(
                            ArbeidsgiverNotificationCloseRequest(
                                publisher.requests.single().eksternId,
                                create.content.tag,
                                meldingstype,
                            ),
                        )
                    fixture.snapshot().getValue(closeId).let { close ->
                        close.state shouldBe "SENT"
                        close.attempt shouldBe 1
                        close.sourceId shouldBe create.id
                    }
                }
            }
        }

        test("V9 guessed identity fails both queued and newly derived closes after V11 without publishing") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("8")
                val create = LegacyCreate()
                val publisher = FakeArbeidsgiverNotificationPublisher()
                fixture.insertLegacyCreate(create)
                fixture.publishHistorically(create, publisher)
                fixture.deleteInbox(create.eventId)

                fixture.migrateTo("10")
                fixture.createExternalId(create.id) shouldBe create.id.toString()
                publisher.requests.single().eksternId shouldBe create.eventId.toString()
                (create.id == create.eventId) shouldBe false
                val pendingId = fixture.insertDependent(create, externalId = create.id.toString())

                fixture.migrate()
                fixture.createExternalId(create.id) shouldBe null
                fixture.createExternalId(pendingId) shouldBe null
                fixture.worker(publisher).runOnce()
                fixture.assertUnresolvedCloseFailed(pendingId, create.id)
                publisher.closeRequests shouldBe emptyList()

                val derivedId = fixture.deriveClose(create)
                fixture.createExternalId(derivedId) shouldBe null
                fixture.worker(publisher).runOnce()
                fixture.assertUnresolvedCloseFailed(derivedId, create.id)
                publisher.closeRequests shouldBe emptyList()
                publisher.requests.size shouldBe 1
                fixture.snapshot().getValue(create.id).state shouldBe "SENT"
            }
        }

        listOf(null, "synthetic-explicit-external-id").forEach { explicitId ->
            val identityMode = if (explicitId == null) "omitted" else "explicit"
            test("post-V11 $identityMode CREATE identity survives inbox deletion through worker publication and close") {
                PostgresTestFixture().use { fixture ->
                    fixture.migrate()
                    val create = LegacyCreate()
                    val publisher = FakeArbeidsgiverNotificationPublisher()
                    if (explicitId == null) {
                        fixture.insertLegacyCreate(create)
                    } else {
                        fixture.insertTriggerProbe(
                            InboxMessage(create.eventId, create.reference, create.content),
                            requireNotNull(create.content.toDeliveryDraft(create.reference)).copy(createExternalId = explicitId),
                            id = create.id,
                        )
                    }
                    val frozenId = explicitId ?: create.eventId.toString()
                    fixture.createExternalId(create.id) shouldBe frozenId
                    fixture.deleteInbox(create.eventId)
                    fixture.snapshot().getValue(create.id).inboxEventId shouldBe null

                    fixture.worker(publisher).runOnce()
                    publisher.requests.single().eksternId shouldBe frozenId
                    fixture.snapshot().getValue(create.id).state shouldBe "SENT"
                    val closeId = fixture.deriveClose(create)
                    fixture.worker(publisher).runOnce()

                    publisher.closeRequests shouldBe
                        listOf(
                            ArbeidsgiverNotificationCloseRequest(
                                publisher.requests.single().eksternId,
                                create.content.tag,
                                create.content.meldingstype,
                            ),
                        )
                    fixture.snapshot().getValue(closeId).let { close ->
                        close.externalId shouldBe frozenId
                        close.sourceId shouldBe create.id
                        close.state shouldBe "SENT"
                    }
                }
            }
        }
    })

private suspend fun PostgresTestFixture.publishHistorically(
    create: LegacyCreate,
    publisher: FakeArbeidsgiverNotificationPublisher,
) {
    publisher.publish(
        ArbeidsgiverNotificationRequest(
            virksomhetsnummer = create.content.orgnummer.value,
            eksternId = create.eventId.toString(),
            grupperingsid = null,
            tag = create.content.tag,
            tekst = create.content.text,
            lenke = create.content.link,
            recipient = ArbeidsgiverNotificationRecipient.AltinnRessurs((create.content.recipient as AltinnResource).resource),
            meldingstype = create.content.meldingstype,
            visibleUntil = null,
        ),
    ) shouldBe ArbeidsgiverNotificationResponse.Published
    dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE delivery SET state = 'SENT', attempt = 2 WHERE id = ?").use { statement ->
            statement.setObject(1, create.id)
            statement.executeUpdate() shouldBe 1
        }
    }
}

private suspend fun PostgresTestFixture.deriveClose(create: LegacyCreate): UUID {
    val inbox = InboxMessageRepositoryImpl(database)
    val message =
        InboxMessage(
            UUID.randomUUID(),
            create.reference,
            ArbeidsgivervarselInactivate(create.reference, TEST_ORGNUMMER),
        )
    inbox.saveBatch(listOf(message))
    inbox.claim(limit = 10, lease = 5.minutes, maxAttempts = 10).map { it.eventId } shouldBe listOf(message.eventId)
    EffectuateDecision(
        TransactionRunnerImpl(database),
        inbox,
        DeliveryRepositoryImpl(database, dataSource),
    ).effectuate(message, Decision.Processed(emptyList())) shouldBe EffectuationResult.FerdigstillWithDelivery(1)
    return snapshot().values.single { it.inboxEventId == message.eventId }.id
}

private fun PostgresTestFixture.assertUnresolvedCloseFailed(
    id: UUID,
    sourceId: UUID,
) {
    snapshot().getValue(id).let { close ->
        close.state shouldBe "FAILED"
        close.attempt shouldBe 1
        close.externalId shouldBe null
        close.sourceId shouldBe sourceId
        close.error shouldBe "ARBEIDSGIVERVARSEL inactivate is missing frozen external id"
    }
}

private fun PostgresTestFixture.worker(publisher: FakeArbeidsgiverNotificationPublisher) =
    DeliveryWorker(
        repository = DeliveryRepositoryImpl(database, dataSource),
        handlers =
            mapOf(
                Channel.ARBEIDSGIVERVARSEL to
                    ArbeidsgivervarselChannelHandler(publisher, FakeNarmesteLederLookup(), NoDeliveryMetrics),
            ),
        drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
        config =
            LeaseDrainConfig(
                interval = 1.seconds,
                batchSize = 10,
                leaseDuration = 5.minutes,
                leaseBudgetFraction = 0.8,
                maxAttempts = 10,
                maxConsecutiveItemFailures = 3,
            ),
        metrics = NoDeliveryMetrics,
    )
