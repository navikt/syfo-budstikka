package no.nav.budstikka.infrastructure.database.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Unit-of-work: kjører [block] i ÉN databasetransaksjon. Grensen eies av kalleren (typisk en
 * use-case i `application`), ikke av det enkelte repository — slik at flere skrivinger (f.eks.
 * delivery-insert + inbox-status) commits alt-eller-ingenting per melding (jf. `docs/adr`,
 * beslutnings-workeren i #56).
 *
 * Operasjoner som kjøres inne i [transaction] må IKKE åpne sin egen transaksjon; de bruker den
 * ambiente transaksjonen Exposed setter på tråden. Eksterne oppslag (PDL/KRR) skjer UTENFOR
 * blokken — en pooled DB-connection holdes aldri åpen over nettverks-I/O.
 */
interface TransactionRunner {
    suspend fun <T> transaction(block: () -> T): T
}

class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> transaction(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }
}
