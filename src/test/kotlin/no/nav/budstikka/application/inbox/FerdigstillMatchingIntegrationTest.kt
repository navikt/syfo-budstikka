package no.nav.budstikka.application.inbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.delivery.ArbeidsgivervarselChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.contract.AltinnResource
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
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
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
                DeliveryRepositoryImpl(fixture.database, fixture.dataSource)

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
                            tag = "Dialogmøte",
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
                    NoDeliveryMetrics,
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
                        recipient = AltinnResource("producer-resource"),
                        tag = "Dialogmøte",
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
                        tag = "Dialogmøte",
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

        test("FERDIGSTILL cancels matching RECEIVED and ordinary CLAIMED CREATEs before materialization") {
            listOf(
                InboxMessageState.RECEIVED to 841,
                InboxMessageState.CLAIMED to 842,
            ).forEach { (createState, suffix) ->
                val (inbox, deliveries) = repositories()
                val effectuate = effectuator(inbox, deliveries)
                val reference = "unmaterialized-${createState.name.lowercase()}-ref"
                val create =
                    InboxMessage(
                        UUID.fromString("00000000-0000-0000-0000-000000000$suffix"),
                        reference,
                        BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "pending"),
                    )
                val inactivate =
                    InboxMessage(
                        UUID.fromString("00000000-0000-0000-0000-000000000${suffix + 10}"),
                        reference,
                        BrukervarselInactivate(reference, TEST_SYKMELDT),
                    )
                inbox.saveBatch(listOf(create, inactivate))
                fixture.database.transact {
                    InboxMessageTable.update({ InboxMessageTable.eventId eq create.eventId }) {
                        it[state] = createState.name
                        if (createState == InboxMessageState.CLAIMED) {
                            it[nextAttemptTime] = Clock.System.now() + lease
                        }
                    }
                    InboxMessageTable.update({ InboxMessageTable.eventId eq inactivate.eventId }) {
                        it[state] = InboxMessageState.CLAIMED.name
                        it[nextAttemptTime] = Clock.System.now() + lease
                    }
                }

                effectuate.effectuate(inactivate, Decision.Processed(emptyList())) shouldBe EffectuationResult.Completed

                inboxState(create.eventId) shouldBe "PROCESSED"
                fixture.database.transact {
                    InboxMessageTable
                        .selectAll()
                        .where { InboxMessageTable.eventId eq create.eventId }
                        .single()[InboxMessageTable.attempt] shouldBe 0
                    DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.count() shouldBe 0L
                }
                effectuate.effectuate(create, createDecision(create)) shouldBe EffectuationResult.Skipped
            }
        }

        test("simultaneous FERDIGSTILL events cancel one matching CREATE without locking each other") {
            val (inbox, deliveries) = repositories()
            val reference = "simultaneous-ferdigstill-ref"
            val create =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000861"),
                    reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "pending"),
                )
            val inactivates =
                listOf(862, 863).map { suffix ->
                    InboxMessage(
                        UUID.fromString("00000000-0000-0000-0000-000000000$suffix"),
                        reference,
                        BrukervarselInactivate(reference, TEST_SYKMELDT),
                    )
                }
            inbox.saveBatch(listOf(create) + inactivates)
            fixture.database.transact {
                inactivates.forEach { inactivate ->
                    InboxMessageTable.update({ InboxMessageTable.eventId eq inactivate.eventId }) {
                        it[state] = InboxMessageState.CLAIMED.name
                        it[nextAttemptTime] = Clock.System.now() + lease
                    }
                }
            }
            val inactivateEventIds = inactivates.map { it.eventId }
            val claimedFerdigstillLocks = CountDownLatch(inactivateEventIds.size)

            fun coordinatedEffectuator() =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        afterClaimedLock = { eventId ->
                            if (eventId in inactivateEventIds) {
                                claimedFerdigstillLocks.countDown()
                                claimedFerdigstillLocks.awaitOrFail(
                                    "FERDIGSTILL effectuation did not lock concurrently",
                                )
                            }
                        },
                    ),
                    deliveries,
                )

            coroutineScope {
                val results =
                    inactivates
                        .map { inactivate ->
                            async(Dispatchers.IO) {
                                coordinatedEffectuator().effectuate(inactivate, Decision.Processed(emptyList()))
                            }
                        }.awaitAll()
                results.count { it == EffectuationResult.Completed } shouldBe 1
                results.count { it == EffectuationResult.FerdigstillWithoutMatch } shouldBe 1
            }

            inboxState(create.eventId) shouldBe "PROCESSED"
            inactivates.forEach { inactivate -> inboxState(inactivate.eventId) shouldBe "PROCESSED" }
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.count() shouldBe 0L
            }
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

        test("FERDIGSTILL locks an awakened WAIT and ordinary CLAIMED sibling before cancelling both") {
            val (awakenedWait, inactivate) = prepareAwakenedWait(3)
            val (inbox, deliveries) = repositories()
            val claimedSibling =
                InboxMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000861"),
                    awakenedWait.reference,
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "claimed sibling"),
                )
            saveAndClaim(inbox, claimedSibling)

            val cancellationLock = TransactionLockBarrier()
            val createLockAttempts = CountDownLatch(2)
            val cancellationEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        afterUnmaterializedCreateLock = { cancellationLock.hold() },
                    ),
                    deliveries,
                )
            val createEffectuate =
                effectuator(
                    LockCoordinatingInboxRepository(
                        inbox,
                        beforeClaimedLock = { eventId ->
                            if (eventId == awakenedWait.eventId || eventId == claimedSibling.eventId) {
                                createLockAttempts.countDown()
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
                val createEffectuations =
                    listOf(awakenedWait, claimedSibling).map { create ->
                        async(Dispatchers.IO) { createEffectuate.effectuate(create, createDecision(create)) }
                    }
                createLockAttempts.awaitOrFail("CREATE effectuation did not contend for both locked rows")
                try {
                    createEffectuations.forEach { it.isCompleted shouldBe false }
                } finally {
                    cancellationLock.release()
                }

                cancellation.await() shouldBe EffectuationResult.Completed
                createEffectuations.awaitAll().forEach { it shouldBe EffectuationResult.Skipped }
            }

            inboxState(awakenedWait.eventId) shouldBe "PROCESSED"
            inboxState(claimedSibling.eventId) shouldBe "PROCESSED"
            inboxState(inactivate.eventId) shouldBe "PROCESSED"
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq awakenedWait.reference }.count() shouldBe 0L
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
                        afterUnmaterializedCreateLock = { cancellationLock.hold() },
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
                        beforeUnmaterializedCreateLock = { inactivateLockAttempted.countDown() },
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
    private val beforeUnmaterializedCreateLock: () -> Unit = {},
    private val afterUnmaterializedCreateLock: (UUID) -> Unit = {},
) : InboxMessageRepository by delegate {
    override fun lockClaimedForEffectuationInTransaction(eventId: UUID): Boolean {
        beforeClaimedLock(eventId)
        return delegate.lockClaimedForEffectuationInTransaction(eventId).also { locked ->
            if (locked) {
                afterClaimedLock(eventId)
            }
        }
    }

    override fun lockUnmaterializedCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID> {
        beforeUnmaterializedCreateLock()
        return delegate.lockUnmaterializedCreatesForFerdigstillInTransaction(match).also { eventIds ->
            eventIds.forEach(afterUnmaterializedCreateLock)
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
