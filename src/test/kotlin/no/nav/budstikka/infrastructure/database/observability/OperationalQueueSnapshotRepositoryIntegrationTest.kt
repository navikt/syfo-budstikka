package no.nav.budstikka.infrastructure.database.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.observability.DeliveryQueueKey
import no.nav.budstikka.application.observability.DeliveryQueueState
import no.nav.budstikka.application.observability.InboxQueueState
import no.nav.budstikka.application.observability.QueueStats
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OperationalQueueSnapshotRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val observedAt = Instant.parse("2026-08-30T12:00:00Z")
        val repository = OperationalQueueSnapshotRepositoryImpl(fixture.database)

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        test("groups active durable work and ignores terminal history") {
            fixture.insertInbox(InboxMessageState.RECEIVED, observedAt - 10.minutes)
            fixture.insertInbox(InboxMessageState.CLAIMED, observedAt - 8.minutes, observedAt - 1.minutes)
            fixture.insertInbox(InboxMessageState.CLAIMED, observedAt - 11.minutes, nextAttemptAt = null)
            fixture.insertInbox(InboxMessageState.CLAIMED, observedAt - 7.minutes, observedAt + 1.minutes)
            fixture.insertInbox(InboxMessageState.WAIT, observedAt - 6.minutes, observedAt - 1.minutes)
            fixture.insertInbox(InboxMessageState.WAIT, observedAt - 5.minutes, observedAt + 1.hours)
            fixture.insertInbox(InboxMessageState.FAILED, observedAt - 4.minutes)
            fixture.insertInbox(InboxMessageState.PROCESSED, observedAt - 20.minutes)

            fixture.insertDelivery(Channel.MICROFRONTEND, DeliveryState.READY, observedAt - 9.minutes)
            fixture.insertDelivery(
                Channel.MICROFRONTEND,
                DeliveryState.CLAIMED,
                observedAt - 8.minutes,
                observedAt - 1.minutes,
            )
            fixture.insertDelivery(Channel.MICROFRONTEND, DeliveryState.CLAIMED, observedAt - 10.minutes)
            fixture.insertDelivery(
                Channel.BRUKERVARSEL,
                DeliveryState.CLAIMED,
                observedAt - 7.minutes,
                observedAt + 1.minutes,
            )
            fixture.insertDelivery(Channel.BREV, DeliveryState.FAILED, observedAt - 6.minutes)
            fixture.insertDelivery(Channel.LEDERVARSEL, DeliveryState.SENT, observedAt - 20.minutes)

            val snapshot = repository.snapshot(observedAt)

            snapshot.observedAt shouldBe observedAt
            snapshot.inbox shouldContainExactly
                mapOf(
                    InboxQueueState.DUE to QueueStats(4, observedAt - 11.minutes),
                    InboxQueueState.IN_FLIGHT to QueueStats(1, observedAt - 7.minutes),
                    InboxQueueState.WAITING to QueueStats(1, observedAt - 5.minutes),
                )
            snapshot.deliveries shouldContainExactly
                mapOf(
                    DeliveryQueueKey(Channel.MICROFRONTEND, DeliveryQueueState.DUE) to
                        QueueStats(3, observedAt - 10.minutes),
                    DeliveryQueueKey(Channel.BRUKERVARSEL, DeliveryQueueState.IN_FLIGHT) to
                        QueueStats(1, observedAt - 7.minutes),
                )
        }

        test("returns no groups when there is no active work") {
            val snapshot = repository.snapshot(observedAt)

            snapshot.inbox shouldBe emptyMap()
            snapshot.deliveries shouldBe emptyMap()
        }

        test("due age starts at an expired lease or sending-window retry") {
            fixture.insertInbox(InboxMessageState.WAIT, observedAt - 24.hours, observedAt - 2.minutes)
            fixture.insertInbox(InboxMessageState.CLAIMED, observedAt - 12.hours, observedAt - 5.minutes)
            fixture.insertDelivery(
                Channel.BRUKERVARSEL,
                DeliveryState.CLAIMED,
                observedAt - 12.hours,
                observedAt - 3.minutes,
            )

            val snapshot = repository.snapshot(observedAt)

            snapshot.inbox[InboxQueueState.DUE] shouldBe QueueStats(2, observedAt - 5.minutes)
            snapshot.deliveries[DeliveryQueueKey(Channel.BRUKERVARSEL, DeliveryQueueState.DUE)] shouldBe
                QueueStats(1, observedAt - 3.minutes)
        }
    })

private suspend fun PostgresTestFixture.insertInbox(
    state: InboxMessageState,
    receivedAt: Instant,
    nextAttemptAt: Instant? = null,
) {
    val message = inboxMessage()
    database.transact {
        InboxMessageTable.insert {
            it[eventId] = message.eventId
            it[content] = message.content
            it[reference] = message.reference
            it[InboxMessageTable.state] = state.name
            it[InboxMessageTable.receivedAt] = receivedAt
            if (nextAttemptAt != null) {
                it[nextAttemptTime] = nextAttemptAt
            }
        }
    }
}

private suspend fun PostgresTestFixture.insertDelivery(
    channel: Channel,
    state: DeliveryState,
    createdAt: Instant,
    nextAttemptAt: Instant? = null,
) {
    database.transact {
        DeliveryTable.insert {
            it[id] = UUID.randomUUID()
            it[reference] = "snapshot-test"
            it[operation] = "CREATE"
            it[DeliveryTable.channel] = channel.name
            it[recipientType] = "PERSON"
            it[recipientId] = "recipient"
            it[payload] = inboxMessage().content
            it[DeliveryTable.state] = state.name
            it[DeliveryTable.createdAt] = createdAt
            if (nextAttemptAt != null) {
                it[nextAttemptTime] = nextAttemptAt
            }
        }
    }
}
