package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.application.port.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.NoDispatchMetrics
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.Tag
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.toDeliveryDraft
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.TransactionRunnerImpl
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class FerdigstillMatchingIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun repositories() =
            InboxMessageRepositoryImpl(fixture.database) to
                DeliveryRepositoryImpl(fixture.database)

        fun effectuator(
            inbox: InboxMessageRepository,
            deliveries: DeliveryRepositoryImpl,
        ) = EffectuateDecision(TransactionRunnerImpl(fixture.database), inbox, deliveries)

        suspend fun saveAndClaim(
            inbox: InboxMessageRepositoryImpl,
            message: InboxMessage,
        ) {
            inbox.saveBatch(listOf(message))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).map { it.eventId } shouldBe listOf(message.eventId)
        }

        fun createDecision(message: InboxMessage) =
            Decision.Processed(listOf(requireNotNull(message.content.toDeliveryDraft(message.reference))))

        suspend fun inactivateRows(reference: String) =
            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where {
                        (DeliveryTable.reference eq reference) and
                            (DeliveryTable.operation eq Operation.INACTIVATE.name)
                    }.toList()
            }

        suspend fun inboxState(eventId: UUID) =
            fixture.database.transact {
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq eventId }
                    .single()[InboxMessageTable.state]
            }

        suspend fun prepareAwakenedWait(suffix: Int): Pair<InboxMessage, InboxMessage> {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val reference = "woken-ref-$suffix"
            val create =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000${840 + suffix}"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "wake"),
                )
            val inactivate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000${850 + suffix}"),
                    reference,
                    BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            saveAndClaim(inbox, create)
            effectuate.effectuate(
                create,
                Decision.NotInSendingWindow(Clock.System.now() + 30.minutes, "Closed Sunday"),
            ) shouldBe EffectuationResult.Completed
            fixture.database.transact {
                InboxMessageTable.update({ InboxMessageTable.eventId eq create.eventId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).map { it.eventId } shouldBe listOf(create.eventId)
            saveAndClaim(inbox, inactivate)
            return create to inactivate
        }

        test("matches each supported FERDIGSTILL channel against its stored CREATE delivery") {
            val cases =
                listOf(
                    Triple(
                        BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "bruker"),
                        BrukervarselInactivate("bruker-ref", TEST_SYKMELDT),
                        Channel.BRUKERVARSEL,
                    ),
                    Triple(
                        LedervarselCreate(
                            TEST_SYKMELDT,
                            TEST_ORGNUMMER,
                            Oppgavetype.DIALOGMOTE_INNKALLING,
                            "leder",
                        ),
                        LedervarselInactivate("leder-ref", TEST_SYKMELDT),
                        Channel.LEDERVARSEL,
                    ),
                    Triple(
                        ArbeidsgivervarselCreate(
                            orgnummer = TEST_ORGNUMMER,
                            recipient = NarmesteLeder(TEST_SYKMELDT),
                            tag = Tag.DIALOGMOETE,
                            text = "arbeidsgiver",
                            link = "https://nav.no/arbeidsgiver",
                            meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                        ),
                        ArbeidsgivervarselInactivate("arbeidsgiver-ref", TEST_ORGNUMMER),
                        Channel.ARBEIDSGIVERVARSEL,
                    ),
                )

            cases.forEachIndexed { index, (create, inactivate, channel) ->
                val (inbox, deliveries) = repositories()
                val effectuate = effectuator(inbox, deliveries)
                val reference =
                    when (inactivate) {
                        is BrukervarselInactivate -> inactivate.reference
                        is LedervarselInactivate -> inactivate.reference
                        is ArbeidsgivervarselInactivate -> inactivate.reference
                        else -> error("Only supported FERDIGSTILL variants are test data")
                    }
                val createEventId = UUID.fromString("00000000-0000-0000-0000-000000000${index + 801}")
                val inactivateEventId = UUID.fromString("00000000-0000-0000-0000-000000000${index + 811}")
                val createMessage = InboxMessage(createEventId, reference, create)
                val inactivateMessage = InboxMessage(inactivateEventId, reference, inactivate)

                saveAndClaim(inbox, createMessage)
                effectuate.effectuate(createMessage, createDecision(createMessage)) shouldBe EffectuationResult.Completed
                saveAndClaim(inbox, inactivateMessage)
                effectuate.effectuate(inactivateMessage, Decision.Processed(emptyList())) shouldBe
                    EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)

                val row = inactivateRows(reference).single()
                row[DeliveryTable.channel] shouldBe channel.name
                row[DeliveryTable.recipientId] shouldBe
                    when (channel) {
                        Channel.ARBEIDSGIVERVARSEL -> TEST_ORGNUMMER.value
                        else -> TEST_SYKMELDT.value
                    }
                when (channel) {
                    Channel.BRUKERVARSEL ->
                        row[DeliveryTable.payload] shouldBe BrukervarselInactivate(reference, TEST_SYKMELDT)

                    Channel.LEDERVARSEL ->
                        row[DeliveryTable.payload] shouldBe LedervarselInactivate(reference, TEST_SYKMELDT)

                    Channel.ARBEIDSGIVERVARSEL -> {
                        row[DeliveryTable.payload] shouldBe create
                        row[DeliveryTable.createExternalId] shouldBe createEventId.toString()
                    }

                    else -> error("Unsupported test channel")
                }
            }
        }

        test("ARBEIDSGIVERVARSEL closes with the CREATE external id after inbox retention") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val publisher = FakeArbeidsgiverNotificationPublisher()
            val handler =
                ArbeidsgivervarselChannelHandler(
                    publisher,
                    FakeNarmesteLederLookup(),
                    NoDispatchMetrics,
                )
            val reference = "retained-create-external-id"
            val createEventId = UUID.fromString("00000000-0000-0000-0000-000000000825")
            val inactivateEventId = UUID.fromString("00000000-0000-0000-0000-000000000826")
            val create =
                InboxMessage(
                    createEventId,
                    reference,
                    ArbeidsgivervarselCreate(
                        orgnummer = TEST_ORGNUMMER,
                        recipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
                        tag = Tag.DIALOGMOETE,
                        text = "arbeidsgiver",
                        link = "https://nav.no/arbeidsgiver",
                        meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                    ),
                )
            val inactivate =
                InboxMessage(
                    inactivateEventId,
                    reference,
                    ArbeidsgivervarselInactivate(reference, TEST_ORGNUMMER),
                )

            saveAndClaim(inbox, create)
            effectuate.effectuate(create, createDecision(create)) shouldBe EffectuationResult.Completed
            val createDelivery =
                deliveries
                    .claim(limit = 1, lease = lease, maxAttempts = 10, channels = setOf(Channel.ARBEIDSGIVERVARSEL))
                    .single()
            createDelivery.createExternalId shouldBe createEventId.toString()
            handler.handle(createDelivery) shouldBe DeliveryOutcome.Sent
            publisher.requests.single().eksternId shouldBe createEventId.toString()
            deliveries.markSent(createDelivery.id) shouldBe true

            fixture.database.transact {
                InboxMessageTable.deleteWhere { InboxMessageTable.eventId eq createEventId }
                val storedCreate = DeliveryTable.selectAll().where { DeliveryTable.id eq createDelivery.id }.single()
                storedCreate[DeliveryTable.inboxEventId] shouldBe null
                storedCreate[DeliveryTable.createExternalId] shouldBe createEventId.toString()
            }

            saveAndClaim(inbox, inactivate)
            effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)
            val inactivateDelivery =
                deliveries
                    .claim(limit = 1, lease = lease, maxAttempts = 10, channels = setOf(Channel.ARBEIDSGIVERVARSEL))
                    .single()
            inactivateDelivery.createExternalId shouldBe createEventId.toString()
            handler.handle(inactivateDelivery) shouldBe DeliveryOutcome.Sent
            publisher.closeRequests shouldBe
                listOf(
                    ArbeidsgiverNotificationCloseRequest(
                        eksternId = createEventId.toString(),
                        tag = Tag.DIALOGMOETE,
                        meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                    ),
                )
        }

        test("FERDIGSTILL matches the CREATE channel and partition anchor, not only its reference") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val reference = "anchored-ref"
            val matchingCreate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000818"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "matching"),
                )
            val differentAnchor =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000819"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT_2, Varseltype.BESKJED, "wrong anchor"),
                )
            val differentChannel =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000820"),
                    reference,
                    LedervarselCreate(
                        TEST_SYKMELDT,
                        TEST_ORGNUMMER,
                        Oppgavetype.DIALOGMOTE_INNKALLING,
                        "wrong channel",
                    ),
                )
            val inactivate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000821"),
                    reference,
                    BrukervarselInactivate(reference, TEST_SYKMELDT),
                )

            listOf(matchingCreate, differentAnchor, differentChannel).forEach { create ->
                saveAndClaim(inbox, create)
                effectuate.effectuate(create, createDecision(create)) shouldBe EffectuationResult.Completed
            }
            saveAndClaim(inbox, inactivate)
            effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)

            val inactivateRow = inactivateRows(reference).single()
            inactivateRow[DeliveryTable.channel] shouldBe Channel.BRUKERVARSEL.name
            inactivateRow[DeliveryTable.recipientId] shouldBe TEST_SYKMELDT.value
            inactivateRow[DeliveryTable.payload] shouldBe BrukervarselInactivate(reference, TEST_SYKMELDT)
        }

        test("missing CREATE and Ditt Sykefravær both become terminal no-ops") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val noMatch =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000823"),
                    "no-match-ref",
                    BrukervarselInactivate("no-match-ref", TEST_SYKMELDT),
                )
            val unsupported =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000824"),
                    "unsupported-ref",
                    DittSykefravaerInactivate("unsupported-ref", TEST_SYKMELDT),
                )

            saveAndClaim(inbox, noMatch)
            effectuate.effectuate(noMatch, Decision.Processed(emptyList())) shouldBe EffectuationResult.FerdigstillWithoutMatch
            saveAndClaim(inbox, unsupported)
            effectuate.effectuate(unsupported, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithoutSupportedRuntimeChannel

            inboxState(noMatch.eventId) shouldBe "PROCESSED"
            inboxState(unsupported.eventId) shouldBe "PROCESSED"
            fixture.database.transact { DeliveryTable.selectAll().count() } shouldBe 0L
        }

        test("FERDIGSTILL cancels a WAIT CREATE before it materializes a delivery") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val reference = "wait-ref"
            val create =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000831"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "wait"),
                )
            val inactivate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000832"),
                    reference,
                    BrukervarselInactivate(reference, TEST_SYKMELDT),
                )

            saveAndClaim(inbox, create)
            effectuate.effectuate(
                create,
                Decision.NotInSendingWindow(Clock.System.now() + 30.minutes, "Closed Sunday"),
            ) shouldBe EffectuationResult.Completed
            saveAndClaim(inbox, inactivate)

            effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe EffectuationResult.Completed

            inboxState(create.eventId) shouldBe "PROCESSED"
            inboxState(inactivate.eventId) shouldBe "PROCESSED"
            fixture.database.transact { DeliveryTable.selectAll().count() } shouldBe 0L
        }

        test("FERDIGSTILL cancels a matching WAIT duplicate when CREATE delivery already exists") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val reference = "materialized-with-wait-duplicate-ref"
            val materializedCreate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000833"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "materialized"),
                )
            val waitingDuplicate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000834"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "waiting duplicate"),
                )
            val inactivate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000835"),
                    reference,
                    BrukervarselInactivate(reference, TEST_SYKMELDT),
                )

            saveAndClaim(inbox, materializedCreate)
            effectuate.effectuate(materializedCreate, createDecision(materializedCreate)) shouldBe EffectuationResult.Completed
            saveAndClaim(inbox, waitingDuplicate)
            effectuate.effectuate(
                waitingDuplicate,
                Decision.NotInSendingWindow(Clock.System.now() + 30.minutes, "Closed Sunday"),
            ) shouldBe EffectuationResult.Completed
            saveAndClaim(inbox, inactivate)

            effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)

            inboxState(waitingDuplicate.eventId) shouldBe "PROCESSED"
            inactivateRows(reference) shouldHaveSize 1
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.count() shouldBe 2L
            }
            effectuate.effectuate(waitingDuplicate, createDecision(waitingDuplicate)) shouldBe EffectuationResult.Skipped
        }

        test("FERDIGSTILL cancels every matching WAIT duplicate before any can materialize") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val reference = "multiple-wait-duplicates-ref"
            val waitingCreates =
                listOf(
                    InboxMessage(
                        UUID.fromString("00000000-0000-0000-0000-000000000836"),
                        reference,
                        BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "first waiting duplicate"),
                    ),
                    InboxMessage(
                        UUID.fromString("00000000-0000-0000-0000-000000000837"),
                        reference,
                        BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "second waiting duplicate"),
                    ),
                )
            val inactivate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000838"),
                    reference,
                    BrukervarselInactivate(reference, TEST_SYKMELDT),
                )

            waitingCreates.forEach { create ->
                saveAndClaim(inbox, create)
                effectuate.effectuate(
                    create,
                    Decision.NotInSendingWindow(Clock.System.now() + 30.minutes, "Closed Sunday"),
                ) shouldBe EffectuationResult.Completed
            }
            saveAndClaim(inbox, inactivate)

            effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe EffectuationResult.Completed

            waitingCreates.forEach { create ->
                inboxState(create.eventId) shouldBe "PROCESSED"
                effectuate.effectuate(create, createDecision(create)) shouldBe EffectuationResult.Skipped
            }
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.count() shouldBe 0L
            }
        }

        test("FERDIGSTILL cancels its locked awakened WAIT CREATE when another matching CREATE materializes") {
            val (waitingCreate, inactivate) = prepareAwakenedWait(3)
            val (inbox, deliveries) = repositories()
            val waitingCreateLock = TransactionLockBarrier()
            val cancellationEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        afterWaitingCreateLock = { waitingCreateLock.hold() },
                    ),
                    deliveries,
                )
            val materializedCreate =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000861"),
                    waitingCreate.reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "materialized"),
                )
            saveAndClaim(inbox, materializedCreate)

            coroutineScope {
                val cancellation =
                    async(Dispatchers.IO) {
                        cancellationEffectuate.effectuate(inactivate, Decision.Processed(emptyList()))
                    }
                waitingCreateLock.awaitLock()
                try {
                    effectuator(inbox, deliveries).effectuate(
                        materializedCreate,
                        createDecision(materializedCreate),
                    ) shouldBe EffectuationResult.Completed
                } finally {
                    waitingCreateLock.release()
                }
                cancellation.await() shouldBe EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)
            }

            inboxState(waitingCreate.eventId) shouldBe "PROCESSED"
            inboxState(materializedCreate.eventId) shouldBe "PROCESSED"
            inboxState(inactivate.eventId) shouldBe "PROCESSED"
            inactivateRows(waitingCreate.reference) shouldHaveSize 1
            inactivateRows(waitingCreate.reference).single()[DeliveryTable.payload] shouldBe
                BrukervarselInactivate(waitingCreate.reference, TEST_SYKMELDT)

            effectuator(inbox, deliveries).effectuate(waitingCreate, createDecision(waitingCreate)) shouldBe
                EffectuationResult.Skipped
            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.reference eq waitingCreate.reference }
                    .count() shouldBe 2L
            }
        }

        test("FERDIGSTILL winning the CREATE row lock blocks effectuation and cancels the awakened CREATE") {
            val (create, inactivate) = prepareAwakenedWait(1)
            val (inbox, deliveries) = repositories()
            val cancellationLock = TransactionLockBarrier()
            val createLockAttempted = CountDownLatch(1)
            val cancellationEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        afterWaitingCreateLock = { cancellationLock.hold() },
                    ),
                    deliveries,
                )
            val createEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        beforeClaimedLock = { eventId ->
                            if (eventId == create.eventId) {
                                createLockAttempted.countDown()
                            }
                        },
                    ),
                    deliveries,
                )

            coroutineScope {
                val cancellation =
                    async(Dispatchers.IO) {
                        cancellationEffectuate.effectuate(inactivate, Decision.Processed(emptyList()))
                    }
                cancellationLock.awaitLock()
                val createEffectuation =
                    async(Dispatchers.IO) { createEffectuate.effectuate(create, createDecision(create)) }
                createLockAttempted.awaitOrFail("CREATE effectuation did not contend for the locked row")
                try {
                    createEffectuation.isCompleted shouldBe false
                } finally {
                    cancellationLock.release()
                }

                cancellation.await() shouldBe EffectuationResult.Completed
                createEffectuation.await() shouldBe EffectuationResult.Skipped
            }

            inboxState(create.eventId) shouldBe "PROCESSED"
            inboxState(inactivate.eventId) shouldBe "PROCESSED"
            fixture.database.transact { DeliveryTable.selectAll().count() } shouldBe 0L
        }

        test("CREATE winning the row lock blocks FERDIGSTILL before it derives INAKTIVER") {
            val (create, inactivate) = prepareAwakenedWait(2)
            val (inbox, deliveries) = repositories()
            val createLock = TransactionLockBarrier()
            val inactivateLockAttempted = CountDownLatch(1)
            val createEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        afterClaimedLock = { eventId ->
                            if (eventId == create.eventId) {
                                createLock.hold()
                            }
                        },
                    ),
                    deliveries,
                )
            val inactivateEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        beforeWaitingCreateLock = { inactivateLockAttempted.countDown() },
                    ),
                    deliveries,
                )

            coroutineScope {
                val createEffectuation =
                    async(Dispatchers.IO) { createEffectuate.effectuate(create, createDecision(create)) }
                createLock.awaitLock()
                val inactivateEffectuation =
                    async(Dispatchers.IO) {
                        inactivateEffectuate.effectuate(inactivate, Decision.Processed(emptyList()))
                    }
                inactivateLockAttempted.awaitOrFail("FERDIGSTILL did not contend for the locked CREATE row")
                try {
                    inactivateEffectuation.isCompleted shouldBe false
                } finally {
                    createLock.release()
                }

                createEffectuation.await() shouldBe EffectuationResult.Completed
                inactivateEffectuation.await() shouldBe EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)
            }

            fixture.database.transact { DeliveryTable.selectAll().count() } shouldBe 2L
            inactivateRows(create.reference).single()[DeliveryTable.payload] shouldBe
                BrukervarselInactivate(create.reference, TEST_SYKMELDT)
        }

        test("concurrent claims of an awakened WAIT row produce one claimant") {
            val (inbox, deliveries) = repositories()
            val effectuate = effectuator(inbox, deliveries)
            val message =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000871"),
                    "concurrent-wait-ref",
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "wait"),
                )
            saveAndClaim(inbox, message)
            effectuate.effectuate(
                message,
                Decision.NotInSendingWindow(Clock.System.now() + 30.minutes, "Closed Sunday"),
            )
            fixture.database.transact {
                InboxMessageTable.update({ InboxMessageTable.eventId eq message.eventId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }

            val claims =
                coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { inbox.claim(limit = 1, lease = lease, maxAttempts = 10) },
                        async(Dispatchers.Default) { inbox.claim(limit = 1, lease = lease, maxAttempts = 10) },
                    ).awaitAll()
                }

            claims.flatten().map { it.eventId } shouldHaveSize 1
            claims.flatten().single().eventId shouldBe message.eventId
        }
    })

private class LockCoordinatingInboxRepository(
    private val delegate: InboxMessageRepository,
    private val beforeClaimedLock: (UUID) -> Unit = {},
    private val afterClaimedLock: (UUID) -> Unit = {},
    private val beforeWaitingCreateLock: () -> Unit = {},
    private val afterWaitingCreateLock: (UUID) -> Unit = {},
) : InboxMessageRepository by delegate {
    override fun lockClaimedForEffectuationInTransaction(eventId: UUID): Boolean {
        beforeClaimedLock(eventId)
        return delegate.lockClaimedForEffectuationInTransaction(eventId).also { locked ->
            if (locked) {
                afterClaimedLock(eventId)
            }
        }
    }

    override fun lockWaitingCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID> {
        beforeWaitingCreateLock()
        return delegate.lockWaitingCreatesForFerdigstillInTransaction(match).also { eventIds ->
            eventIds.forEach(afterWaitingCreateLock)
        }
    }
}

private class TransactionLockBarrier {
    private val acquired = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun hold() {
        acquired.countDown()
        release.awaitOrFail("Timed out waiting to release the transaction row lock")
    }

    fun awaitLock() {
        acquired.awaitOrFail("Timed out waiting for the transaction row lock")
    }

    fun release() {
        release.countDown()
    }
}

private fun CountDownLatch.awaitOrFail(message: String) {
    check(await(5, TimeUnit.SECONDS)) { message }
}
