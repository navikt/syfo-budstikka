package no.nav.budstikka.infrastructure.database.config

import no.nav.budstikka.application.port.TransactionRunner
import org.jetbrains.exposed.v1.jdbc.Database

class TransactionRunnerImpl(
    private val database: Database,
) : TransactionRunner {
    override suspend fun <T> transaction(block: () -> T): T = database.transact(block)
}
