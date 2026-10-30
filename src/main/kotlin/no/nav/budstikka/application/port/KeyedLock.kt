package no.nav.budstikka.application.port

/**
 * Mutual exclusion per [key] across replicas. Unlike [TransactionRunner], [block] may do external
 * I/O: it runs while the lock is held, and the lock is released when the block completes or fails.
 */
interface KeyedLock {
    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T
}
