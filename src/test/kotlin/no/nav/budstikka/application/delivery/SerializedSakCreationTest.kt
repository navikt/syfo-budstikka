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

class SerializedSakCreationTest :
    FunSpec({
        test("creates the sak while holding the lock for its merkelapp and grupperingsid") {
            val lock = RecordingLock()
            val delegate = LockObservingPublisher(lock)

            SerializedSakCreation(delegate, lock).createSak(sakCreate("mote-1:relasjon")) shouldBe
                ArbeidsgiverNotificationResponse.Published

            lock.keys shouldBe listOf(sakLockKey("Dialogmøte", "mote-1:relasjon"))
            delegate.createdWhileLocked shouldBe listOf(true)
        }

        test("uses one key per downstream sak") {
            sakLockKey("Dialogmøte", "a") shouldBe sakLockKey("Dialogmøte", "a")
            sakLockKey("Dialogmøte", "a") shouldNotBe sakLockKey("Dialogmøte", "b")
            sakLockKey("Dialogmøte", "a") shouldNotBe sakLockKey("Oppfølging", "a")
        }

        test("delegates other operations without the lock") {
            val lock = RecordingLock()
            val delegate = FakeArbeidsgiversakPublisher()
            val publisher = SerializedSakCreation(delegate, lock)

            publisher.extendSak(
                ArbeidsgiversakExtendRequest("DIALOGMOTE:relasjon", "Dialogmøte", Instant.parse("2027-03-02T09:00:00Z"), "event-1"),
            )
            publisher.updateKalenderavtale(
                KalenderavtaleUpdateRequest("Dialogmøte", "avtale-1", Kalenderavtaletilstand.AVLYST, null, null, "event-2"),
            )

            lock.keys shouldBe emptyList()
            delegate.requests.size shouldBe 2
        }
    })

private fun sakCreate(grupperingsid: String) =
    ArbeidsgiversakCreateRequest(
        virksomhetsnummer = TEST_ORGNUMMER.value,
        grupperingsid = grupperingsid,
        tag = "Dialogmøte",
        title = "Dialogmøte",
        link = null,
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

    override suspend fun createSak(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse {
        createdWhileLocked += lock.held
        return delegate.createSak(request)
    }
}
