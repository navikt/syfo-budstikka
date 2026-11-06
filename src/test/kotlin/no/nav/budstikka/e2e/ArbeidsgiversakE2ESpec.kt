package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.Arbeidsgiversak
import no.nav.budstikka.contract.Arbeidsgivervarsel
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.Kalenderavtale
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.FakePersonNameLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")

@Tags("E2E")
class ArbeidsgiversakE2ESpec :
    FunSpec({
        test("calendar, notification without a link, and cancellation use the saved leader snapshot and same sak") {
            val sakPublisher = FakeArbeidsgiversakPublisher()
            val notificationPublisher = FakeArbeidsgiverNotificationPublisher()
            val lookup =
                FakeNarmesteLederLookup().apply {
                    registerActive(
                        TEST_SYKMELDT,
                        TEST_ORGNUMMER,
                        NarmesteLederRelasjon(LEADER, listOf("leder@example.test"), TEST_LEDERRELASJON_ID),
                    )
                }
            val lookupCalls = AtomicInteger()
            val names =
                FakePersonNameLookup().apply {
                    register(TEST_SYKMELDT, PersonName("Kari", null, "Nordmann"))
                }
            val kalenderavtaleReference = "avtale-${UUID.randomUUID()}"
            val leaderSak = "DIALOGMOTE:$TEST_LEDERRELASJON_ID"

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { sakPublisher }
                    provide<ArbeidsgiverNotificationPublisher> { notificationPublisher }
                    provide<NarmesteLederLookup> {
                        NarmesteLederLookup { sykmeldt, orgnummer ->
                            lookupCalls.incrementAndGet()
                            lookup.findActive(sykmeldt, orgnummer)
                        }
                    }
                    provide<PersonNameLookup> { names }
                }.use { app ->
                    val dispatches =
                        listOf(
                            Budstikka.kalenderavtaleCreate(
                                eventId = EventId.new(),
                                reference = kalenderavtaleReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                                text = "Dialogmøte",
                                link = "https://nav.no/e2e/dialogmote",
                                startAt = Instant.parse("2026-11-02T09:00:00Z"),
                                email = Kalenderavtale.EmailNotification.plainText("Innkalling", "Du er innkalt"),
                            ),
                            Budstikka.arbeidsgivervarselCreate(
                                eventId = EventId.new(),
                                reference = "e2e-varsel-${UUID.randomUUID()}",
                                orgnummer = TEST_ORGNUMMER,
                                recipient = Arbeidsgivervarsel.NarmesteLeder(TEST_SYKMELDT),
                                sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                                text = "Du er innkalt til dialogmøte",
                                sendingWindow = SendingWindow.ONGOING,
                            ),
                            Budstikka.kalenderavtaleUpdate(
                                eventId = EventId.new(),
                                reference = kalenderavtaleReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                                tilstand = Kalenderavtale.Tilstand.AVLYST,
                                saksstatus = Arbeidsgiversak.Status.FERDIG,
                            ),
                        )
                    dispatches.forEach { app.produce(it) }

                    eventually(30.seconds) {
                        app.deliveryStatesFor(dispatches.map { it.eventId.value }) shouldContainExactly
                            List(dispatches.size) { DeliveryState.SENT.name }

                        sakPublisher.sakExtensions shouldHaveSize 3
                        sakPublisher.sakExtensions.forEach { it.grupperingsid shouldBe leaderSak }
                        sakPublisher.saksstatuser.single().apply {
                            grupperingsid shouldBe leaderSak
                            status shouldBe Saksstatus.FERDIG
                        }
                        sakPublisher.kalenderavtaleCreates.single().apply {
                            grupperingsid shouldBe leaderSak
                            tag shouldBe "Dialogmøte"
                            eksternId shouldBe kalenderavtaleReference
                            recipient.externalVarsling?.epostadresser shouldBe listOf("leder@example.test")
                        }
                        notificationPublisher.requests.single().apply {
                            grupperingsid shouldBe leaderSak
                            tag shouldBe "Dialogmøte"
                            lenke shouldBe
                                "https://www.ekstern.dev.nav.no/arbeidsgiver/sykmeldte/$TEST_LEDERRELASJON_ID"
                        }
                        sakPublisher.kalenderavtaleUpdates.single().apply {
                            eksternId shouldBe kalenderavtaleReference
                            tag shouldBe "Dialogmøte"
                            tilstand shouldBe Kalenderavtaletilstand.AVLYST
                        }
                        lookupCalls.get() shouldBe dispatches.size
                        names.lookups shouldBe dispatches.size
                        app.database.transact {
                            val snapshot =
                                DeliveryTable
                                    .selectAll()
                                    .where { DeliveryTable.inboxEventId eq dispatches[1].eventId.value }
                                    .single()
                            snapshot[DeliveryTable.snapshotState] shouldBe "RESOLVED"
                            snapshot[DeliveryTable.leaderRelationId] shouldBe TEST_LEDERRELASJON_ID
                            snapshot[DeliveryTable.nameFirst] shouldBe "Kari"
                            snapshot[DeliveryTable.nameLast] shouldBe "Nordmann"
                        }
                    }
                }
        }

        test("a kalenderavtale for a Sykmeldt without an active leader reaches terminal FAILED without calling Fager") {
            val publisher = FakeArbeidsgiversakPublisher()
            val notificationPublisher = FakeArbeidsgiverNotificationPublisher()
            val names = FakePersonNameLookup()

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { publisher }
                    provide<ArbeidsgiverNotificationPublisher> { notificationPublisher }
                    provide<NarmesteLederLookup> { FakeNarmesteLederLookup() }
                    provide<PersonNameLookup> { names }
                }.use { app ->
                    val dispatch =
                        Budstikka.kalenderavtaleCreate(
                            eventId = EventId.new(),
                            reference = "e2e-avtale-${UUID.randomUUID()}",
                            orgnummer = TEST_ORGNUMMER,
                            sykmeldt = TEST_SYKMELDT,
                            sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                            text = "Dialogmøte",
                            link = "https://nav.no/e2e/dialogmote",
                            startAt = Instant.parse("2026-11-02T09:00:00Z"),
                        )
                    app.produce(dispatch)

                    eventually(30.seconds) {
                        app.inboxStateFor(dispatch.eventId.value) shouldBe InboxMessageState.FAILED.name
                        app.deliveryStatesFor(listOf(dispatch.eventId.value)) shouldContainExactly listOf(null)
                        publisher.requests shouldBe emptyList()
                        notificationPublisher.requests shouldBe emptyList()
                        names.lookups shouldBe 0
                    }
                }
        }
    })

private fun BudstikkaTestApp.produce(dispatch: EncodedDispatch) =
    produce(topic = budstikkaTopic, key = dispatch.key, value = dispatch.value, headers = dispatch.headers)

/** Delivery states in the order of [eventIds]; a missing delivery is reported as `null`. */
private suspend fun BudstikkaTestApp.deliveryStatesFor(eventIds: List<UUID>): List<String?> =
    database.transact {
        val states =
            DeliveryTable
                .selectAll()
                .where { DeliveryTable.inboxEventId inList eventIds }
                .associate { it[DeliveryTable.inboxEventId] to it[DeliveryTable.state] }
        eventIds.map { states[it] }
    }

private suspend fun BudstikkaTestApp.inboxStateFor(eventId: UUID): String? =
    database.transact {
        InboxMessageTable
            .selectAll()
            .where { InboxMessageTable.eventId eq eventId }
            .singleOrNull()
            ?.get(InboxMessageTable.state)
    }
