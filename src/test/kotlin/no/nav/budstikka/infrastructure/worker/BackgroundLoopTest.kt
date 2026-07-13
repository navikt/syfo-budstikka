package no.nav.budstikka.infrastructure.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.Heartbeat
import no.nav.budstikka.infrastructure.MutableClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BackgroundLoopTest :
    FunSpec({
        test("liveness goes stale once the loop stops beating") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, 5.minutes)
            val ranOnce = CountDownLatch(1)
            val loop =
                BackgroundLoop("test-worker", 10.milliseconds, heartbeat) {
                    ranOnce.countDown()
                }

            loop.start()
            ranOnce.await(5, TimeUnit.SECONDS) shouldBe true
            loop.isAlive() shouldBe true
            loop.close()

            // The stopped loop no longer records, so advancing past the threshold surfaces as stale.
            clock.current += 10.minutes
            loop.isAlive() shouldBe false
        }

        test("a failing iteration keeps the loop alive") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, 5.minutes)
            val failed = CountDownLatch(1)
            val loop =
                BackgroundLoop("failing-worker", 10.milliseconds, heartbeat) {
                    failed.countDown()
                    error("boom")
                }

            loop.start()
            failed.await(5, TimeUnit.SECONDS) shouldBe true
            loop.isAlive() shouldBe true
            loop.close()
        }

        test("default stale threshold scales with slow intervals") {
            // An hourly loop (e.g. the future cleanup worker, B42) must not be declared dead between
            // healthy rounds: the derived threshold covers at least two missed rounds.
            BackgroundLoop.defaultStaleThreshold(1.hours) shouldBe 2.hours
            // Fast loops keep the conservative floor so a slow-but-healthy round is not flagged.
            BackgroundLoop.defaultStaleThreshold(3.seconds) shouldBe Heartbeat.DEFAULT_STALE_THRESHOLD
        }
    })
