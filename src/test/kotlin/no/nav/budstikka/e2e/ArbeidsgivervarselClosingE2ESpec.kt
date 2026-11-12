package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationDeleteRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.Arbeidsgiversak
import no.nav.budstikka.contract.Arbeidsgivervarsel
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakePersonNameLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@Tags("E2E")
class ArbeidsgivervarselClosingE2ESpec :
    FunSpec({
        test("reference-based close is idempotent through lost acknowledgements and does not cancel a later create") {
            val publisher = StatefulNotificationPublisher()
            val names = FakePersonNameLookup()
            val leaderCalls = AtomicInteger()
            val saker = FakeArbeidsgiversakPublisher()
            BudstikkaTestApp
                .start(
                    configOverrides =
                        mapOf(
                            "workers.inboxMessage.intervalSeconds" to "1",
                            "workers.delivery.intervalSeconds" to "1",
                            "workers.delivery.leaseSeconds" to "1",
                        ),
                ) {
                    provide<ArbeidsgiverNotificationPublisher> { publisher }
                    provide<ArbeidsgiversakPublisher> { saker }
                    provide<PersonNameLookup> { names }
                    provide<NarmesteLederLookup> {
                        NarmesteLederLookup { _, _ ->
                            leaderCalls.incrementAndGet()
                            NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
                        }
                    }
                }.use { app ->
                    suspend fun sendAndAwait(dispatch: EncodedDispatch) {
                        app.produce(app.budstikkaTopic, dispatch.key, dispatch.value, dispatch.headers)
                        eventually(30.seconds) {
                            app.database.transact {
                                DeliveryTable
                                    .selectAll()
                                    .where { DeliveryTable.inboxEventId eq dispatch.eventId.value }
                                    .single()[DeliveryTable.state] shouldBe "SENT"
                            }
                        }
                    }

                    fun create(reference: String) =
                        Budstikka.arbeidsgivervarselCreate(
                            EventId.new(),
                            reference,
                            TEST_ORGNUMMER,
                            Arbeidsgivervarsel.NarmesteLeder(TEST_SYKMELDT),
                            Arbeidsgiversak.Type.DIALOGMOTE,
                            "Synthetic task",
                            sendingWindow = SendingWindow.ONGOING,
                        )

                    fun close(reference: String) =
                        Budstikka.arbeidsgivervarselInactivate(
                            EventId.new(),
                            reference,
                            TEST_ORGNUMMER,
                            Arbeidsgiversak.Type.DIALOGMOTE,
                        )

                    val normal = UUID.randomUUID().toString()
                    sendAndAwait(create(normal))
                    publisher.notifications.size shouldBe 1
                    sendAndAwait(close(normal))
                    sendAndAwait(close(normal))
                    publisher.notifications.isEmpty() shouldBe true
                    publisher.missingDeletes.get() shouldBe 1

                    val lostAck = UUID.randomUUID().toString()
                    sendAndAwait(create(lostAck))
                    val target = publisher.notifications.keys.single()
                    publisher.loseNextDeleteAcknowledgement.set(true)
                    sendAndAwait(close(lostAck))
                    publisher.notifications.isEmpty() shouldBe true
                    publisher.deletions.count { it.tag to it.eksternId == target } shouldBe 2
                    publisher.missingDeletes.get() shouldBe 2

                    // A close addresses the current target; it does not install a cancellation tombstone.
                    val laterCreate = UUID.randomUUID().toString()
                    sendAndAwait(close(laterCreate))
                    publisher.notifications.isEmpty() shouldBe true
                    sendAndAwait(create(laterCreate))
                    publisher.notifications.size shouldBe 1
                    publisher.creations.size shouldBe 3
                    leaderCalls.get() shouldBe 3
                    names.lookups shouldBe 3
                    saker.requests.size shouldBe 3
                }
        }
    })

/** Models real target removal, including the ambiguity after a lost delete acknowledgement. */
private class StatefulNotificationPublisher : ArbeidsgiverNotificationPublisher {
    val notifications = ConcurrentHashMap<Pair<String, String>, ArbeidsgiverNotificationRequest>()
    val creations = CopyOnWriteArrayList<ArbeidsgiverNotificationRequest>()
    val deletions = CopyOnWriteArrayList<ArbeidsgiverNotificationDeleteRequest>()
    val missingDeletes = AtomicInteger()
    val loseNextDeleteAcknowledgement = AtomicBoolean()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        creations += request
        notifications[request.tag to request.eksternId] = request
        return ArbeidsgiverNotificationResponse.Published
    }

    override suspend fun delete(request: ArbeidsgiverNotificationDeleteRequest): ArbeidsgiverNotificationResponse {
        deletions += request
        if (notifications.remove(request.tag to request.eksternId) == null) missingDeletes.incrementAndGet()
        if (loseNextDeleteAcknowledgement.getAndSet(false)) error("Lost synthetic deletion acknowledgement")
        return ArbeidsgiverNotificationResponse.Published
    }
}
