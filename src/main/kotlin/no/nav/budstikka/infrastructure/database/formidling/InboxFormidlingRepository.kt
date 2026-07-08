package no.nav.budstikka.infrastructure.database.formidling

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.util.UUID
import kotlin.time.Clock

data class DeadLetterRecord(
    val payload: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
    val kafkaKey: String?,
    val failureReason: String,
    val errorMessage: String?,
)

interface InboxFormidlingRepository {
    suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean

    suspend fun lagreFeilet(record: DeadLetterRecord)
}

class InboxFormidlingRepositoryImpl(
    private val database: Database,
) : InboxFormidlingRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean =
        database.transact {
            val now = Clock.System.now()
            InboxFormidlingTable
                .insertIgnore {
                    it[InboxFormidlingTable.eventId] = eventId
                    it[InboxFormidlingTable.payload] = payload
                    it[InboxFormidlingTable.receivedAt] = now
                }.insertedCount > 0
        }

    override suspend fun lagreFeilet(record: DeadLetterRecord) {
        database.transact {
            val now = Clock.System.now()
            DeadLetterFormidlingTable.insertIgnore {
                it[DeadLetterFormidlingTable.payload] = record.payload
                it[DeadLetterFormidlingTable.topic] = record.topic
                it[DeadLetterFormidlingTable.partition] = record.partition
                it[DeadLetterFormidlingTable.kafkaOffset] = record.kafkaOffset
                it[DeadLetterFormidlingTable.kafkaKey] = record.kafkaKey
                it[DeadLetterFormidlingTable.failureReason] = record.failureReason
                it[DeadLetterFormidlingTable.errorMessage] = record.errorMessage
                it[DeadLetterFormidlingTable.receivedAt] = now
            }
        }
    }
}
