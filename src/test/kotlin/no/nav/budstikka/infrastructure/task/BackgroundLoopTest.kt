package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.Heartbeat
import no.nav.budstikka.infrastructure.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackgroundLoopTest :
    FunSpec({
        test("liveness goes stale once the loop stops beating") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, Duration.ofMinutes(5))
            val ranOnce = CountDownLatch(1)
            val loop =
                BackgroundLoop("test-task", Duration.ofMillis(10), heartbeat) {
                    ranOnce.countDown()
                }

            loop.start()
            ranOnce.await(5, TimeUnit.SECONDS) shouldBe true
            loop.isAlive() shouldBe true
            loop.close()

            // The stopped loop no longer records, so advancing past the threshold surfaces as stale.
            clock.current = clock.current.plus(Duration.ofMinutes(10))
            loop.isAlive() shouldBe false
        }

        test("a failing iteration keeps the loop alive") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, Duration.ofMinutes(5))
            val failed = CountDownLatch(1)
            val loop =
                BackgroundLoop("failing-task", Duration.ofMillis(10), heartbeat) {
                    failed.countDown()
                    error("boom")
                }

            loop.start()
            failed.await(5, TimeUnit.SECONDS) shouldBe true
            loop.isAlive() shouldBe true
            loop.close()
        }

        test("default stale threshold scales with slow intervals") {
            // An hourly loop (e.g. the future cleanup task, B42) must not be declared dead between
            // healthy rounds: the derived threshold covers at least two missed rounds.
            BackgroundLoop.defaultStaleThreshold(Duration.ofHours(1)) shouldBe Duration.ofHours(2)
            // Fast loops keep the conservative floor so a slow-but-healthy round is not flagged.
            BackgroundLoop.defaultStaleThreshold(Duration.ofSeconds(3)) shouldBe Heartbeat.DEFAULT_STALE_THRESHOLD
        }
    })
