package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
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
        test("a close deletes the notification without leader lookups, and a repeated close counts as done") {
            withClosingApp {
                val reference = UUID.randomUUID().toString()
                sendAndAwait(create(reference))
                publisher.notifications.size shouldBe 1

                sendAndAwait(close(reference))
                sendAndAwait(close(reference))

                publisher.notifications.shouldBeEmpty()
                publisher.missingDeletes.get() shouldBe 1
                leaderCalls.get() shouldBe 1
                saker.requests.size shouldBe 1
            }
        }

        test("a close whose acknowledgement is lost is retried and counts as done") {
            withClosingApp {
                val reference = UUID.randomUUID().toString()
                sendAndAwait(create(reference))
                val target = publisher.notifications.keys.single()
                publisher.loseNextDeleteAcknowledgement.set(true)

                sendAndAwait(close(reference))

                publisher.notifications.shouldBeEmpty()
                publisher.deletions.count { (it.tag to it.eksternId) == target } shouldBe 2
                publisher.missingDeletes.get() shouldBe 1
            }
        }

        // A close addresses the current notification; it leaves nothing behind that stops a later create.
        test("a close before the create does not stop the create") {
            withClosingApp {
                val reference = UUID.randomUUID().toString()
                sendAndAwait(close(reference))
                publisher.notifications.shouldBeEmpty()

                sendAndAwait(create(reference))

                publisher.notifications.size shouldBe 1
            }
        }
    })

private fun create(reference: String) =
    Budstikka.arbeidsgivervarselCreate(
        EventId.new(),
        reference,
        TEST_ORGNUMMER,
        Arbeidsgivervarsel.NarmesteLeder(TEST_SYKMELDT),
        Arbeidsgiversak.Type.DIALOGMOTE,
        "Synthetic task",
        sendingWindow = SendingWindow.ONGOING,
    )

private fun close(reference: String) =
    Budstikka.arbeidsgivervarselInactivate(
        EventId.new(),
        reference,
        TEST_ORGNUMMER,
        Arbeidsgiversak.Type.DIALOGMOTE,
    )

/** A running Budstikka with a Fager stand-in that keeps the notifications it holds. */
private class ClosingApp(
    private val app: BudstikkaTestApp,
    val publisher: StatefulNotificationPublisher,
    val saker: FakeArbeidsgiversakPublisher,
    val leaderCalls: AtomicInteger,
) {
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
}

private suspend fun withClosingApp(block: suspend ClosingApp.() -> Unit) {
    val publisher = StatefulNotificationPublisher()
    val saker = FakeArbeidsgiversakPublisher()
    val leaderCalls = AtomicInteger()
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
            provide<PersonNameLookup> { FakePersonNameLookup() }
            provide<NarmesteLederLookup> {
                NarmesteLederLookup { _, _ ->
                    leaderCalls.incrementAndGet()
                    NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
                }
            }
        }.use { app -> ClosingApp(app, publisher, saker, leaderCalls).block() }
}

/** Models real target removal, including the ambiguity after a lost delete acknowledgement. */
private class StatefulNotificationPublisher : ArbeidsgiverNotificationPublisher {
    val notifications = ConcurrentHashMap<Pair<String, String>, ArbeidsgiverNotificationRequest>()
    val deletions = CopyOnWriteArrayList<ArbeidsgiverNotificationDeleteRequest>()
    val missingDeletes = AtomicInteger()
    val loseNextDeleteAcknowledgement = AtomicBoolean()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
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
