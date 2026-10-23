package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederRelasjon
import no.nav.budstikka.contract.Arbeidsgiversak
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.Kalenderavtale
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
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
        test("case, calendar event, calendar update and case status reach Fager and SENT") {
            val publisher = FakeArbeidsgiversakPublisher()
            val lookup =
                FakeNarmesteLederLookup().apply {
                    registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, listOf("leder@example.test")))
                }
            val caseId = "mote-${UUID.randomUUID()}"
            val calendarReference = "avtale-${UUID.randomUUID()}"

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { publisher }
                    provide<NarmesteLederLookup> { lookup }
                }.use { app ->
                    val dispatches =
                        listOf(
                            Budstikka.arbeidsgiversakCreate(
                                eventId = EventId.new(),
                                reference = caseId,
                                orgnummer = TEST_ORGNUMMER,
                                recipient = Arbeidsgiversak.NarmesteLeder(TEST_SYKMELDT),
                                tag = "Dialogmøte",
                                caseId = caseId,
                                title = "Dialogmøte",
                            ),
                            Budstikka.kalenderavtaleCreate(
                                eventId = EventId.new(),
                                reference = calendarReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                tag = "Dialogmøte",
                                caseId = caseId,
                                text = "Dialogmøte",
                                link = "https://nav.no/e2e/dialogmote",
                                startAt = Instant.parse("2026-11-02T09:00:00Z"),
                                email = Kalenderavtale.LeaderEmail.plainText("Innkalling", "Du er innkalt"),
                            ),
                            Budstikka.kalenderavtaleUpdate(
                                eventId = EventId.new(),
                                reference = calendarReference,
                                orgnummer = TEST_ORGNUMMER,
                                sykmeldt = TEST_SYKMELDT,
                                tag = "Dialogmøte",
                                newState = Kalenderavtale.State.CANCELLED,
                            ),
                            Budstikka.arbeidsgiversakStatus(
                                eventId = EventId.new(),
                                reference = caseId,
                                orgnummer = TEST_ORGNUMMER,
                                tag = "Dialogmøte",
                                caseId = caseId,
                                status = Arbeidsgiversak.Status.DONE,
                            ),
                        )
                    dispatches.forEach { app.produce(it) }

                    eventually(30.seconds) {
                        app.deliveryStatesFor(dispatches.map { it.eventId.value }) shouldContainExactly
                            List(dispatches.size) { DeliveryState.SENT.name }

                        publisher.caseCreates.single().apply {
                            grupperingsid shouldBe caseId
                            recipient shouldBe ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT)
                        }
                        publisher.calendarCreates.single().apply {
                            grupperingsid shouldBe caseId
                            eksternId shouldBe calendarReference
                            recipient.externalVarsling?.epostadresser shouldBe listOf("leder@example.test")
                        }
                        publisher.calendarUpdates.single().apply {
                            eksternId shouldBe calendarReference
                            nyTilstand shouldBe Kalenderavtaletilstand.AVLYST
                        }
                        publisher.caseStatuses.single().apply {
                            grupperingsid shouldBe caseId
                            status shouldBe Saksstatus.FERDIG
                        }
                    }
                }
        }

        test("a case for a Sykmeldt without an active leader reaches terminal FAILED without calling Fager") {
            val publisher = FakeArbeidsgiversakPublisher()

            BudstikkaTestApp
                .start {
                    provide<ArbeidsgiversakPublisher> { publisher }
                    provide<NarmesteLederLookup> { FakeNarmesteLederLookup() }
                }.use { app ->
                    val dispatch =
                        Budstikka.arbeidsgiversakCreate(
                            eventId = EventId.new(),
                            reference = "e2e-case",
                            orgnummer = TEST_ORGNUMMER,
                            recipient = Arbeidsgiversak.NarmesteLeder(TEST_SYKMELDT),
                            tag = "Dialogmøte",
                            caseId = "mote-${UUID.randomUUID()}",
                            title = "Dialogmøte",
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
