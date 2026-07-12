package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.toKotlinDuration

data class ClaimedDelivery(
    val id: UUID,
    val inboxEventId: UUID?,
    val channel: Channel,
    val payload: DispatchContent,
)

/**
 * Skriver frosne [DeliveryDraft] som `delivery`-rader. Én inbox-hendelse gir 0..N leveranser.
 * Åpner IKKE egen transaksjon: kjøres inne i
 * [no.nav.budstikka.infrastructure.database.config.TransactionRunner.transaction] sammen med inbox-
 * status-overgangen, slik at beslutnings-workeren (#56) effektuerer én melding alt-eller-ingenting.
 * `id`/`state`/`attempt` fylles av DB-defaults (uuidv7 / 'READY' / 0).
 */
interface DeliveryRepository {
    fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    )

    suspend fun claim(
        limit: Int,
        lease: Duration,
        channels: Set<Channel>,
    ): List<ClaimedDelivery>

    suspend fun markSent(deliveryId: UUID): Boolean

    suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean
}

class DeliveryRepositoryImpl(
    private val database: Database,
) : DeliveryRepository {
    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        if (draft.isEmpty()) {
            return
        }
        DeliveryTable.batchInsert(draft) { draftEntry ->
            this[DeliveryTable.inboxEventId] = inboxEventId
            this[DeliveryTable.reference] = draftEntry.reference
            this[DeliveryTable.operation] = draftEntry.operation.name
            this[DeliveryTable.channel] = draftEntry.channel.name
            val (type, id) = draftEntry.recipient.toColumns()
            this[DeliveryTable.recipientType] = type
            this[DeliveryTable.recipientId] = id
            this[DeliveryTable.payload] = draftEntry.content
            this[DeliveryTable.createdAt] = Clock.System.now()
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        channels: Set<Channel>,
    ): List<ClaimedDelivery> {
        require(limit > 0) { "limit must be greater than 0" }
        require(channels.isNotEmpty()) { "channels must not be empty" }
        return database.transact {
            val now = Clock.System.now()
            val channelNames = channels.map(Channel::name)
            val claimed =
                DeliveryTable
                    .selectAll()
                    .where {
                        (
                            (DeliveryTable.state eq DeliveryState.READY.name) or
                                (
                                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and
                                        (DeliveryTable.nextAttemptTime lessEq now)
                                )
                        ) and
                            (DeliveryTable.channel inList channelNames)
                    }.orderBy(
                        DeliveryTable.createdAt to SortOrder.ASC,
                        DeliveryTable.id to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        ClaimedDelivery(
                            id = row[DeliveryTable.id],
                            inboxEventId = row[DeliveryTable.inboxEventId],
                            channel = Channel.valueOf(row[DeliveryTable.channel]),
                            payload = row[DeliveryTable.payload],
                        )
                    }
            if (claimed.isNotEmpty()) {
                val leaseDeadline = now + lease.toKotlinDuration()
                DeliveryTable.update({ DeliveryTable.id inList claimed.map { it.id } }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[nextAttemptTime] = leaseDeadline
                    it[attempt] = attempt + 1
                }
            }
            claimed
        }
    }

    override suspend fun markSent(deliveryId: UUID): Boolean =
        markClaimedAsTerminal(deliveryId, state = DeliveryState.SENT, errorMessage = null)

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean {
        require(reason.isNotBlank()) { "reason must not be blank" }
        return markClaimedAsTerminal(deliveryId, state = DeliveryState.FAILED, errorMessage = reason)
    }

    private suspend fun markClaimedAsTerminal(
        deliveryId: UUID,
        state: DeliveryState,
        errorMessage: String?,
    ): Boolean =
        database.transact {
            DeliveryTable.update({
                (DeliveryTable.id eq deliveryId) and (DeliveryTable.state eq DeliveryState.CLAIMED.name)
            }) {
                it[DeliveryTable.state] = state.name
                it[DeliveryTable.nextAttemptTime] = null
                it[DeliveryTable.errorMessage] = errorMessage
            } > 0
        }
}

private fun Recipient.toColumns(): Pair<String, String> =
    when (this) {
        is Recipient.Person -> "PERSON" to ident.value
        is Recipient.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }
