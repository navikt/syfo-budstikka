package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds

private val oslo = ZoneId.of("Europe/Oslo")

private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    fromEpochMilliseconds(
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, oslo).toInstant().toEpochMilli()
    )

class SendingWindowGateTest :
    FunSpec({
        fun brukervarselContent(): DispatchContent =
            BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "test")

        test("tirsdag 12:00 (åpent) gir Processed") {
            val clock = MutableClock(zdt(2025, 2, 11, 12))
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldBe emptyList()
        }

        test("tirsdag 03:00 (stengt) gir NotInSendingWindow med nextRetry i fremtiden") {
            val now = zdt(2025, 2, 11, 3)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            (notInWindow.nextRetry > now) shouldBe true
        }

        test("søndag 12:00 (stengt) gir NotInSendingWindow") {
            val now = zdt(2025, 2, 9, 12)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val notInWindow = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>()
            (notInWindow.nextRetry > now) shouldBe true
        }

        test("åpent vindu passerer deliveries uendret") {
            val clock = MutableClock(zdt(2025, 2, 11, 12))
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val drafts = listOf(
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

        test("nextRetry for søndag peker til mandag 08:00") {
            val now = zdt(2025, 2, 9, 10)
            val clock = MutableClock(now)
            val gate = SendingWindowGate(clock)
            val event = Dispatch(reference = "ref-1", content = brukervarselContent())
            val decision = gate.resolve(event).apply(emptyList())

            val nextRetry = decision.shouldBeInstanceOf<Decision.NotInSendingWindow>().nextRetry
            (nextRetry - now).inWholeHours shouldBe 22
        }
    })
