package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.TransactionRunner

class FakeTransactionRunner : TransactionRunner {
    var transactionCount = 0
        private set

    override suspend fun <T> transaction(block: () -> T): T {
        transactionCount++
        return block()
    }
}
