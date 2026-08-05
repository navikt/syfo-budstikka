package no.nav.budstikka.application.port

/**
 * Runs [block] in one database transaction. Nested repository operations use that ambient
 * transaction; external I/O must happen before entering the block.
 */
interface TransactionRunner {
    suspend fun <T> transaction(block: () -> T): T
}
