package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock

data class ReplayableDeadLetter(
    val id: UUID,
    val eventId: UUID,
    val payload: String,
)

data class DeadLetterRecord(
    val payload: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
    val kafkaKey: String?,
    val eventId: UUID?,
    val failureReason: String,
    val errorMessage: String?,
)

interface DeadLetterMessageRepository {
    suspend fun saveBatch(records: List<DeadLetterRecord>)

    suspend fun findReplayable(
        limit: Int,
        offset: Long,
    ): List<ReplayableDeadLetter>

    suspend fun deleteByIds(ids: List<UUID>)
}

class DeadLetterMessageRepositoryImpl(
    private val database: Database,
) : DeadLetterMessageRepository {
    override suspend fun saveBatch(records: List<DeadLetterRecord>) {
        if (records.isEmpty()) {
            return
        }
        database.transact {
            val now = Clock.System.now()
            DeadLetterMessageTable.batchInsert(records) { record ->
                this[DeadLetterMessageTable.payload] = record.payload
                this[DeadLetterMessageTable.topic] = record.topic
                this[DeadLetterMessageTable.partition] = record.partition
                this[DeadLetterMessageTable.kafkaOffset] = record.kafkaOffset
                this[DeadLetterMessageTable.kafkaKey] = record.kafkaKey
                this[DeadLetterMessageTable.eventId] = record.eventId
                this[DeadLetterMessageTable.failureReason] = record.failureReason
                this[DeadLetterMessageTable.errorMessage] = record.errorMessage
                this[DeadLetterMessageTable.receivedAt] = now
            }
        }
    }

    override suspend fun findReplayable(
        limit: Int,
        offset: Long,
    ): List<ReplayableDeadLetter> {
        require(limit > 0) { "limit must be greater than 0" }
        require(offset >= 0) { "offset must not be negative" }
        return database.transact {
            DeadLetterMessageTable
                .select(
                    DeadLetterMessageTable.id,
                    DeadLetterMessageTable.eventId,
                    DeadLetterMessageTable.payload,
                ).where {
                    (DeadLetterMessageTable.failureReason eq "UNPARSEABLE_PAYLOAD") and
                        DeadLetterMessageTable.eventId.isNotNull()
                }.orderBy(
                    DeadLetterMessageTable.receivedAt to SortOrder.ASC,
                    DeadLetterMessageTable.id to SortOrder.ASC,
                ).limit(limit)
                .offset(offset)
                .map { row ->
                    ReplayableDeadLetter(
                        id = row[DeadLetterMessageTable.id],
                        eventId = requireNotNull(row[DeadLetterMessageTable.eventId]),
                        payload = row[DeadLetterMessageTable.payload],
                    )
                }
        }
    }

    override suspend fun deleteByIds(ids: List<UUID>) {
        if (ids.isEmpty()) {
            return
        }
        database.transact {
            DeadLetterMessageTable.deleteWhere { DeadLetterMessageTable.id inList ids }
        }
    }
}
