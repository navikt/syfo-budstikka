package no.nav.budstikka.application.port

/**
 * Mutual exclusion per [key] across replicas. Unlike [TransactionRunner], [block] may do external
 * I/O: it runs while the lock is held, and the lock is released when the block completes or fails.
 *
 * A delivery claim keeps one worker on one delivery row. This lock covers different deliveries that
 * touch the same downstream object, such as two deliveries creating the same sak, whose key is only
 * known at send time.
 */
interface KeyedLock {
    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T
}
