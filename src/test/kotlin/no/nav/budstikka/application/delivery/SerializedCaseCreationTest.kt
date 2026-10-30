package no.nav.budstikka.application.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.application.port.KeyedLock
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import kotlin.time.Instant

class SerializedCaseCreationTest :
    FunSpec({
        test("creates the case while holding the lock for its merkelapp and grupperingsid") {
            val lock = RecordingLock()
            val delegate = LockObservingPublisher(lock)

            SerializedCaseCreation(delegate, lock).createCase(caseCreate("mote-1:relasjon")) shouldBe
                ArbeidsgiverNotificationResponse.Published

            lock.keys shouldBe listOf(caseLockKey("Dialogmøte", "mote-1:relasjon"))
            delegate.createdWhileLocked shouldBe listOf(true)
        }

        test("uses one key per downstream case") {
            caseLockKey("Dialogmøte", "a") shouldBe caseLockKey("Dialogmøte", "a")
            caseLockKey("Dialogmøte", "a") shouldNotBe caseLockKey("Dialogmøte", "b")
            caseLockKey("Dialogmøte", "a") shouldNotBe caseLockKey("Oppfølging", "a")
        }

        test("delegates other operations without the lock") {
            val lock = RecordingLock()
            val delegate = FakeArbeidsgiversakPublisher()
            val publisher = SerializedCaseCreation(delegate, lock)

            publisher.extendCase(
                ArbeidsgiversakExtendRequest("DIALOGMOTE:relasjon", "Dialogmøte", Instant.parse("2027-03-02T09:00:00Z"), "event-1"),
            )
            publisher.updateCalendarEvent(
                KalenderavtaleUpdateRequest("Dialogmøte", "avtale-1", Kalenderavtaletilstand.AVLYST, null, null, "event-2"),
            )

            lock.keys shouldBe emptyList()
            delegate.requests.size shouldBe 2
        }
    })

private fun caseCreate(grupperingsid: String) =
    ArbeidsgiversakCreateRequest(
        virksomhetsnummer = TEST_ORGNUMMER.value,
        grupperingsid = grupperingsid,
        tag = "Dialogmøte",
        tittel = "Dialogmøte",
        lenke = null,
        keepUntil = Instant.parse("2027-03-02T09:00:00Z"),
        recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(PersonIdentifier("00000000000"), TEST_SYKMELDT),
        correlationId = "event-1",
    )

private class RecordingLock : KeyedLock {
    val keys = mutableListOf<String>()
    var held = false
        private set

    override suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        keys += key
        held = true
        try {
            return block()
        } finally {
            held = false
        }
    }
}

private class LockObservingPublisher(
    private val lock: RecordingLock,
    private val delegate: FakeArbeidsgiversakPublisher = FakeArbeidsgiversakPublisher(),
) : ArbeidsgiversakPublisher by delegate {
    val createdWhileLocked = mutableListOf<Boolean>()

    override suspend fun createCase(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse {
        createdWhileLocked += lock.held
        return delegate.createCase(request)
    }
}
