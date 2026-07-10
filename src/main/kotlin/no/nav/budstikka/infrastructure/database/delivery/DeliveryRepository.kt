package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.time.Clock

/**
 * Skriver frosne [DeliveryDraft] som `leveranse`-rader. Én inbox-hendelse gir 0..N leveranser;
 * hele batchen skrives i én transaksjon slik at beslutnings-workeren (#56) kan sette inbox-status
 * i samme tx (alt-eller-ingenting). `id`/`state`/`attempt` fylles av DB-defaults (uuidv7 / 'KLAR' / 0).
 */
interface DeliveryRepository {
    suspend fun save(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    )
}

class DeliveryRepositoryImpl(
    private val database: Database,
) : DeliveryRepository {
    override suspend fun save(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        if (draft.isEmpty()) return
        database.transact {
            draft.forEach { draftEntry ->
                val (type, id) = draftEntry.recipient.toColumns()
                DeliveryTable.insert {
                    it[DeliveryTable.inboxEventId] = inboxEventId
                    it[DeliveryTable.reference] = draftEntry.reference
                    it[DeliveryTable.operation] = draftEntry.operation.name
                    it[DeliveryTable.channel] = draftEntry.channel.name
                    it[DeliveryTable.recipientType] = type
                    it[DeliveryTable.recipientId] = id
                    it[DeliveryTable.payload] = draftEntry.content
                    it[DeliveryTable.createdAt] = Clock.System.now()
                }
            }
        }
    }
}

private fun Recipient.toColumns(): Pair<String, String> =
    when (this) {
        is Recipient.Person -> "PERSON" to ident.value
        is Recipient.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }
