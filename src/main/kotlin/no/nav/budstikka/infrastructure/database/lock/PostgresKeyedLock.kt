package no.nav.budstikka.infrastructure.database.lock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.port.KeyedLock
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [KeyedLock] backed by a transaction-scoped PostgreSQL advisory lock on a connection of its own, so
 * it holds across replicas and the rollback releases it even when the block fails. The key is hashed
 * to 32 bits and never reaches the database; a collision only serializes unrelated work. Waiting
 * longer than [waitTimeout] fails with PostgreSQL's lock timeout, and the caller retries later.
 */
class PostgresKeyedLock(
    private val dataSource: DataSource,
    private val waitTimeout: Duration = 60.seconds,
) : KeyedLock {
    override suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        val connection = withContext(Dispatchers.IO) { acquire(key) }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { connection.release() }
        }
    }

    private fun acquire(key: String): Connection {
        val connection = dataSource.connection
        try {
            connection.autoCommit = false
            connection.prepareStatement(SET_LOCAL_LOCK_TIMEOUT).use { statement ->
                statement.setString(1, "${waitTimeout.inWholeMilliseconds}ms")
                statement.executeQuery().close()
            }
            connection.prepareStatement(ADVISORY_XACT_LOCK).use { statement ->
                statement.setInt(1, KEYED_LOCK_NAMESPACE)
                statement.setInt(2, key.hashCode())
                statement.executeQuery().close()
            }
            return connection
        } catch (failure: Exception) {
            runCatching { connection.release() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun Connection.release() {
        try {
            rollback()
        } finally {
            close()
        }
    }

    internal companion object {
        /** Distinct from the retention cleanup lock namespace. */
        const val KEYED_LOCK_NAMESPACE = 0x424b4559

        private const val SET_LOCAL_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)"
        private const val ADVISORY_XACT_LOCK = "SELECT pg_advisory_xact_lock(?, ?)"
    }
}
