package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.Heartbeat
import no.nav.budstikka.infrastructure.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BaseTaskTest :
    FunSpec({
        test("liveness goes stale once the loop stops beating") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, Duration.ofMinutes(5))
            val ranOnce = CountDownLatch(1)
            val task =
                object : BaseTask("test-task", Duration.ofMillis(10), heartbeat) {
                    override suspend fun runIteration() {
                        ranOnce.countDown()
                    }
                }

            task.start()
            ranOnce.await(5, TimeUnit.SECONDS) shouldBe true
            task.isAlive() shouldBe true
            task.close()

            // The stopped loop no longer records, so advancing past the threshold surfaces as stale.
            clock.current = clock.current.plus(Duration.ofMinutes(10))
            task.isAlive() shouldBe false
        }

        test("a failing iteration keeps the loop alive") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, Duration.ofMinutes(5))
            val failed = CountDownLatch(1)
            val task =
                object : BaseTask("failing-task", Duration.ofMillis(10), heartbeat) {
                    override suspend fun runIteration() {
                        failed.countDown()
                        error("boom")
                    }
                }

            task.start()
            failed.await(5, TimeUnit.SECONDS) shouldBe true
            task.isAlive() shouldBe true
            task.close()
        }
    })
