package no.nav.budstikka.infrastructure.kafka.formidling

import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepository
import java.util.UUID

class ThrowingFormidlingRepository : InboxFormidlingRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = error("DB nede — transient feil")
}
