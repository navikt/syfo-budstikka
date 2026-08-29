package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.dispatch.ReplayableDeadLetter
import java.util.UUID

class FakeDeadLetterRepository(
    private val replayable: MutableList<ReplayableDeadLetter> = mutableListOf(),
    private val calls: MutableList<String> = mutableListOf(),
    private val saveBatchFailure: RuntimeException? = null,
) : DeadLetterMessageRepository {
    val savedDeadLetters = mutableListOf<DeadLetterRecord>()
    var saveBatchCalls = 0
        private set

    override suspend fun saveBatch(records: List<DeadLetterRecord>) {
        saveBatchFailure?.let { throw it }
        saveBatchCalls++
        savedDeadLetters += records
    }

    override suspend fun findReplayable(
        limit: Int,
        offset: Long,
    ): List<ReplayableDeadLetter> {
        calls += "find"
        return replayable.drop(offset.toInt()).take(limit)
    }

    override suspend fun deleteByIds(ids: List<UUID>) {
        calls += "delete"
        replayable.removeAll { it.id in ids }
    }

    fun calls(): List<String> = calls
}
