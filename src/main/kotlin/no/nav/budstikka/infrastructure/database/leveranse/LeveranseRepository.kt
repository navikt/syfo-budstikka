package no.nav.budstikka.infrastructure.database.leveranse

import no.nav.budstikka.domain.beslutning.LeveranseDraft
import no.nav.budstikka.domain.beslutning.Mottaker
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.time.Clock

/**
 * Skriver frosne [LeveranseDraft] som `leveranse`-rader. Én inbox-hendelse gir 0..N leveranser;
 * hele batchen skrives i én transaksjon slik at beslutnings-workeren (#56) kan sette inbox-status
 * i samme tx (alt-eller-ingenting). `id`/`state`/`attempt` fylles av DB-defaults (uuidv7 / 'KLAR' / 0).
 */
interface LeveranseRepository {
    suspend fun save(
        inboxEventId: UUID,
        draft: List<LeveranseDraft>,
    )
}

class LeveranseRepositoryImpl(
    private val database: Database,
) : LeveranseRepository {
    override suspend fun save(
        inboxEventId: UUID,
        draft: List<LeveranseDraft>,
    ) {
        if (draft.isEmpty()) return
        database.transact {
            val now = Clock.System.now()
            draft.forEach { u ->
                val (type, id) = u.mottaker.toColumns()
                LeveranseTable.insert {
                    it[LeveranseTable.inboxEventId] = inboxEventId
                    it[LeveranseTable.referanse] = u.referanse
                    it[LeveranseTable.operation] = u.operation.name
                    it[LeveranseTable.kanal] = u.kanal.name
                    it[LeveranseTable.mottakerType] = type
                    it[LeveranseTable.mottakerId] = id
                    it[LeveranseTable.payload] = u.content
                    it[LeveranseTable.createdAt] = now
                }
            }
        }
    }
}

private fun Mottaker.toColumns(): Pair<String, String> =
    when (this) {
        is Mottaker.Person -> "PERSON" to ident.value
        is Mottaker.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }
