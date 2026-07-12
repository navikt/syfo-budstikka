package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord

class FakeDeadLetterRepository : DeadLetterMessageRepository {
    val savedDeadLetters = mutableListOf<DeadLetterRecord>()
    var saveBatchCalls = 0
        private set

    override suspend fun saveBatch(records: List<DeadLetterRecord>) {
        saveBatchCalls++
        savedDeadLetters += records
    }
}
