package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
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

interface DeadLetterMessageRepository {
    suspend fun saveBatch(records: List<DeadLetterRecord>)
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
                this[DeadLetterMessageTable.failureReason] = record.failureReason
                this[DeadLetterMessageTable.errorMessage] = record.errorMessage
                this[DeadLetterMessageTable.receivedAt] = now
            }
        }
    }
}
