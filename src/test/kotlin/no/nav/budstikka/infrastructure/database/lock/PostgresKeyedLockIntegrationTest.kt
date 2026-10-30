package no.nav.budstikka.infrastructure.database.lock

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import java.sql.SQLException
import java.util.Collections
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Each lock has a pool of its own, like a replica, so holders are separate database sessions. */
class PostgresKeyedLockIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val replicaA = fixture.singleConnectionPool()
        val replicaB = fixture.singleConnectionPool()
        val lockA = PostgresKeyedLock(replicaA)
        val lockB = PostgresKeyedLock(replicaB)

        afterSpec {
            replicaA.close()
            replicaB.close()
            fixture.close()
        }

        test("serializes holders of the same key across sessions") {
            val key = uniqueKey()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val events = Collections.synchronizedList(mutableListOf<String>())

            coroutineScope {
                val first =
                    async {
                        lockA.withLock(key) {
                            events += "first in"
                            firstEntered.complete(Unit)
                            releaseFirst.await()
                            events += "first out"
                        }
                    }
                firstEntered.await()
                val second = async { lockB.withLock(key) { events += "second in" } }
                delay(500.milliseconds)
                events.toList() shouldBe listOf("first in")
                releaseFirst.complete(Unit)
                awaitAll(first, second)
            }

            events.toList() shouldBe listOf("first in", "first out", "second in")
        }

        test("does not wait for another key") {
            val held = uniqueKey()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            coroutineScope {
                val holder =
                    async {
                        lockA.withLock(held) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                withTimeout(5.seconds) { lockB.withLock(uniqueKey()) { "free" } } shouldBe "free"
                release.complete(Unit)
                holder.await()
            }
        }

        test("releases the lock when the block fails") {
            val key = uniqueKey()

            shouldThrow<IllegalStateException> { lockA.withLock(key) { error("downstream failed") } }

            withTimeout(5.seconds) { lockB.withLock(key) { "free" } } shouldBe "free"
        }

        test("fails with a lock timeout after waiting too long") {
            val key = uniqueKey()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val impatient = PostgresKeyedLock(replicaB, waitTimeout = 200.milliseconds)

            coroutineScope {
                val holder =
                    async {
                        lockA.withLock(key) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                shouldThrow<SQLException> { impatient.withLock(key) { "unexpected" } }.sqlState shouldBe "55P03"
                release.complete(Unit)
                holder.await()
            }
            withTimeout(5.seconds) { lockB.withLock(key) { "free" } } shouldBe "free"
        }

        test("returns its connection to the pool after each use") {
            repeat(3) {
                withTimeout(5.seconds) { lockA.withLock(uniqueKey()) { it } } shouldBe it
            }
        }
    })

private fun uniqueKey() = "test-${UUID.randomUUID()}"

private fun PostgresTestFixture.singleConnectionPool() =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = this@singleConnectionPool.jdbcUrl
            username = this@singleConnectionPool.username
            password = this@singleConnectionPool.password
            maximumPoolSize = 1
            minimumIdle = 0
            connectionTimeout = 2_000
        },
    )
