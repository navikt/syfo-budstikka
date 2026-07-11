package no.nav.budstikka.fakes

import no.nav.budstikka.infrastructure.database.config.TransactionRunner

/**
 * Kjører blokken direkte uten en ekte databasetransaksjon. Lar enhetstester verifisere at
 * effektueringen kaller de ambiente repository-operasjonene, uten Postgres/Testcontainers.
 */
class FakeTransactionRunner : TransactionRunner {
    var transactionCount = 0
        private set

    override suspend fun <T> transaction(block: () -> T): T {
        transactionCount++
        return block()
    }
}
