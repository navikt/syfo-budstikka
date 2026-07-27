package no.nav.budstikka.application.port

/**
 * Unit of work: runs [block] in ONE database transaction. The caller (usually an `application` use
 * case) owns the boundary, not an individual repository, so several writes (for example a delivery
 * insert plus inbox status) commit all or nothing per message.
 *
 * Operations inside [transaction] must NOT open a transaction of their own; they use the ambient
 * transaction Exposed sets on the thread. External lookups (PDL/KRR) run OUTSIDE the block: never
 * hold a pooled database connection open over network I/O.
 */
interface TransactionRunner {
    suspend fun <T> transaction(block: () -> T): T
}
