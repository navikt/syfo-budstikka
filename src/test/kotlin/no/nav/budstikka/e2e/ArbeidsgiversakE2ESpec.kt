package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederRelasjon
import no.nav.budstikka.contract.Arbeidsgiversak
import no.nav.budstikka.contract.Arbeidsgivervarsel
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.Kalenderavtale
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")

@Tags("E2E")
class ArbeidsgiversakE2ESpec :
    FunSpec({
        test("calendar event, notification and case status land in the leader relation's case and reach SENT") {
            val casePublisher = FakeArbeidsgiversakPublisher()
            val notificationPublisher = FakeArbeidsgiverNotificationPublisher()
            val lookup =
                FakeNarmesteLederLookup().apply {
                    registerActive(
                        TEST_SYKMELDT,
                        TEST_ORGNUMMER,
                        NarmesteLederRelasjon(LEADER, listOf("leder@example.test"), TEST_LEDERRELASJON_ID),
                    )
                }
            val sak = Arbeidsgiversak(sakId = "mote-${UUID.randomUUID()}", title = "Dialogmøte")
            val calendarReference = "avtale-${UUID.randomUUID()}"
            val leaderCase = "${sak.sakId}:$TEST_LEDERRELASJON_ID"

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { casePublisher }
                    provide<ArbeidsgiverNotificationPublisher> { notificationPublisher }
                    provide<NarmesteLederLookup> { lookup }
                }.use { app ->
                    val dispatches =
                        listOf(
                            Budstikka.kalenderavtaleCreate(
                                eventId = EventId.new(),
                                reference = calendarReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                tag = "Dialogmøte",
                                sak = sak,
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
                                sak = sak,
                                tag = "Dialogmøte",
                                text = "Du er innkalt til dialogmøte",
                                link = "https://nav.no/e2e/dialogmote",
                            ),
                            Budstikka.kalenderavtaleUpdate(
                                eventId = EventId.new(),
                                reference = calendarReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                tag = "Dialogmøte",
                                tilstand = Kalenderavtale.Tilstand.AVLYST,
                            ),
                            Budstikka.arbeidsgiversakStatus(
                                eventId = EventId.new(),
                                reference = sak.sakId,
                                orgnummer = TEST_ORGNUMMER,
                                recipient = Arbeidsgiversak.NarmesteLeder(TEST_SYKMELDT),
                                tag = "Dialogmøte",
                                sak = sak,
                                status = Arbeidsgiversak.Status.FERDIG,
                            ),
                        )
                    dispatches.forEach { app.produce(it) }

                    eventually(30.seconds) {
                        app.deliveryStatesFor(dispatches.map { it.eventId.value }) shouldContainExactly
                            List(dispatches.size) { DeliveryState.SENT.name }

                        casePublisher.caseCreates shouldHaveSize 3
                        casePublisher.caseCreates.forEach {
                            it.grupperingsid shouldBe leaderCase
                            it.recipient shouldBe ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT)
                        }
                        casePublisher.calendarCreates.single().apply {
                            grupperingsid shouldBe leaderCase
                            eksternId shouldBe calendarReference
                            recipient.externalVarsling?.epostadresser shouldBe listOf("leder@example.test")
                        }
                        notificationPublisher.requests.single().grupperingsid shouldBe leaderCase
                        casePublisher.calendarUpdates.single().apply {
                            eksternId shouldBe calendarReference
                            nyTilstand shouldBe Kalenderavtaletilstand.AVLYST
                        }
                        casePublisher.caseStatuses.single().apply {
                            grupperingsid shouldBe leaderCase
                            status shouldBe Saksstatus.FERDIG
                        }
                    }
                }
        }

        test("a case status for a Sykmeldt without an active leader reaches terminal FAILED without calling Fager") {
            val publisher = FakeArbeidsgiversakPublisher()

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { publisher }
                    provide<NarmesteLederLookup> { FakeNarmesteLederLookup() }
                }.use { app ->
                    val dispatch =
                        Budstikka.arbeidsgiversakStatus(
                            eventId = EventId.new(),
                            reference = "e2e-case",
                            orgnummer = TEST_ORGNUMMER,
                            recipient = Arbeidsgiversak.NarmesteLeder(TEST_SYKMELDT),
                            tag = "Dialogmøte",
                            sak = Arbeidsgiversak(sakId = "mote-${UUID.randomUUID()}", title = "Dialogmøte"),
                            status = Arbeidsgiversak.Status.UNDER_BEHANDLING,
                        )
                    app.produce(dispatch)

                    eventually(30.seconds) {
                        app.deliveryStatesFor(listOf(dispatch.eventId.value)) shouldContainExactly
                            listOf(DeliveryState.FAILED.name)
                        publisher.requests shouldBe emptyList()
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
