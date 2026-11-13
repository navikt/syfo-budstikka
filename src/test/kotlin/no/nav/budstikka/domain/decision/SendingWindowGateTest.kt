package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.foundation.calendar.NorwegianRodeDager
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock

private val oslo = TimeZone.of("Europe/Oslo")

class SendingWindowGateTest :
    FunSpec({
        fun brukervarselContent(sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS): DispatchContent =
            BrukervarselCreate(
                TEST_SYKMELDT,
                Varseltype.OPPGAVE,
                "test",
                sendingWindow = sendingWindow,
            )

        test("Tuesday 12:00 (open) gives Processed") {
            val clock = MutableClock(LocalDateTime(2025, 2, 11, 12, 0).toInstant(oslo))
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe emptyList()
        }

        context("SendingWindow ONGOING gives Processed regardless of date") {
            val rodeDager = NorwegianRodeDager.rodeDager(2026).keys
            withData(rodeDager) { day ->
                val gate =
                    SendingWindowGate(
                        MutableClock(
                            LocalDateTime(day.year, day.month.number, day.day, 12, 0).toInstant(oslo),
                        ),
                    )
                val event =
                    Dispatch(reference = "ref-1", content = brukervarselContent(sendingWindow = SendingWindow.ONGOING))

                val decision = gate.resolve(event).apply(emptyList())
                decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe emptyList()
            }
        }

        test("LedervarselCreate with default ONGOING passes the gate even on Sunday") {
            val now = LocalDateTime(2025, 2, 9, 12, 0).toInstant(oslo)
            val gate = SendingWindowGate(MutableClock(now))
            val event =
                Dispatch(
                    reference = "ref-1",
                    content =
                        LedervarselCreate(
                            sykmeldt = TEST_SYKMELDT,
                            orgnummer = TEST_ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = "test",
                        ),
                )
            val decision = gate.resolve(event).apply(emptyList())

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe emptyList()
        }

        test("Saturday: default and explicit opening hours wait until Monday") {
            val now = LocalDateTime(2025, 2, 8, 12, 0).toInstant(oslo)
            val gate = SendingWindowGate(MutableClock(now))
            val contents =
                listOf(
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "test"),
                    ArbeidsgivervarselCreate(
                        orgnummer = TEST_ORGNUMMER,
                        recipient = NarmesteLeder(TEST_SYKMELDT),
                        text = "test",
                    ),
                    LedervarselCreate(
                        sykmeldt = TEST_SYKMELDT,
                        orgnummer = TEST_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = "test",
                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                    ),
                )

            contents.forEach { content ->
                val event = Dispatch(reference = "ref-1", content = content)
                val decision = gate.resolve(event).apply(emptyList())
                decision.shouldBeInstanceOf<Decision.NotInSendingWindow>().nextRetry shouldBe
                    LocalDateTime(2025, 2, 10, 9, 0).toInstant(oslo)
            }
        }

        test("Saturday: ONGOING and the Ledervarsel default window pass") {
            val now = LocalDateTime(2025, 2, 8, 12, 0).toInstant(oslo)
            val gate = SendingWindowGate(MutableClock(now))
            val contents =
                listOf(
                    brukervarselContent(SendingWindow.ONGOING),
                    LedervarselCreate(
                        sykmeldt = TEST_SYKMELDT,
                        orgnummer = TEST_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = "test",
                    ),
                )

            contents.forEach { content ->
                val event = Dispatch(reference = "ref-1", content = content)
                gate.resolve(event).apply(emptyList()).shouldBeInstanceOf<Decision.Processed>()
            }
        }

        test("Tuesday 03:00 (closed) gives NotInSendingWindow with nextRetry in the future") {
            val now = LocalDateTime(2025, 2, 11, 3, 0).toInstant(oslo)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            (notInWindow.nextRetry > now) shouldBe true
        }

        test("Sunday 12:00 (closed) gives NotInSendingWindow") {
            val now = LocalDateTime(2025, 2, 9, 12, 0).toInstant(oslo)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            (notInWindow.nextRetry > now) shouldBe true
        }

        test("Sunday 12:00 (closed) gives the correct reason") {
            val now = LocalDateTime(2025, 2, 9, 12, 0).toInstant(oslo)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            notInWindow.reason shouldBe "Closed Sunday"
        }

        test("closed on Christmas Eve 24.12 gives NotInSendingWindow with reason") {
            val now = LocalDateTime(2025, 12, 24, 12, 0).toInstant(oslo)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            notInWindow.reason shouldBe "Julaften (2025-12-24)"
        }

        test("New Year's Eve 31.12 is open within opening hours") {
            val clock = MutableClock(LocalDateTime(2025, 12, 31, 12, 0).toInstant(oslo))
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe emptyList()
        }

        test("open window passes deliveries unchanged") {
            val clock = MutableClock(LocalDateTime(2025, 2, 11, 12, 0).toInstant(oslo))
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val drafts =
                listOf(
                    DeliveryDraft(
                        reference = "ref-1",
                        operation = Operation.CREATE,
                        channel = Channel.BRUKERVARSEL,
                        recipient = Recipient.Person(TEST_SYKMELDT),
                        content = brukervarselContent(),
                    ),
                )
            val decision = gate.resolve(event).apply(drafts)

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe drafts
        }

        test("nextRetry for Sunday points to Monday 09:00") {
            val now = LocalDateTime(2025, 2, 9, 10, 0).toInstant(oslo)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val nextRetry = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>().nextRetry.toLocalDateTime(oslo)
            nextRetry.dayOfWeek shouldBe DayOfWeek.MONDAY
            nextRetry.hour shouldBe 9
        }
    })
