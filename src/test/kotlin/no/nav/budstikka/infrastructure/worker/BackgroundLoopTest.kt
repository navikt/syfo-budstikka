package no.nav.budstikka.infrastructure.worker

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.budstikka.application.AlreadyLoggedWorkerFailure
import no.nav.budstikka.infrastructure.Heartbeat
import no.nav.budstikka.infrastructure.MutableClock
import org.slf4j.LoggerFactory
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

        test("failing iteration log carries error type without stacktrace") {
            val failed = CountDownLatch(1)
            val loop =
                BackgroundLoop("failing-worker", 10.milliseconds) {
                    failed.countDown()
                    error("boom")
                }
            val logbackLogger = LoggerFactory.getLogger(BackgroundLoop::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                loop.start()
                failed.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                loop.close()
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            val event = appender.list.single { it.formattedMessage.contains("Worker failed in iteration") }
            event.formattedMessage shouldContain "IllegalStateException"
            event.throwableProxy shouldBe null
        }

        test("already logged iteration failure is not logged again") {
            val failed = CountDownLatch(1)
            val loop =
                BackgroundLoop("failing-worker", 10.milliseconds) {
                    failed.countDown()
                    throw AlreadyLoggedWorkerFailure(IllegalStateException("boom"))
                }
            val logbackLogger = LoggerFactory.getLogger(BackgroundLoop::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                loop.start()
                failed.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                loop.close()
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            appender.list.none { it.formattedMessage.contains("Worker failed in iteration") } shouldBe true
        }

        test("records run, duration and failure metrics tagged by worker") {
            val registry = SimpleMeterRegistry()
            val failed = CountDownLatch(1)
            val loop =
                BackgroundLoop("metered-worker", 10.milliseconds, meterRegistry = registry) {
                    failed.countDown()
                    error("boom")
                }

            loop.start()
            failed.await(5, TimeUnit.SECONDS) shouldBe true

            // The failure counter increments inside the loop's catch after the throw; poll briefly so
            // the assertion does not race the worker coroutine.
            eventually(5.seconds) {
                registry
                    .get("worker.failures")
                    .tag("worker", "metered-worker")
                    .counter()
                    .count() shouldBeGreaterThan 0.0
            }
            loop.close()

            registry
                .get("worker.runs")
                .tag("worker", "metered-worker")
                .counter()
                .count() shouldBeGreaterThan 0.0
            registry
                .get("worker.duration")
                .tag("worker", "metered-worker")
                .timer()
                .count() shouldBeGreaterThan 0L
        }

        test("default stale threshold scales with slow intervals") {
            // An hourly loop (e.g. the future cleanup worker, B42) must not be declared dead between
            // healthy rounds: the derived threshold covers at least two missed rounds.
            BackgroundLoop.defaultStaleThreshold(1.hours) shouldBe 2.hours
            // Fast loops keep the conservative floor so a slow-but-healthy round is not flagged.
            BackgroundLoop.defaultStaleThreshold(3.seconds) shouldBe Heartbeat.DEFAULT_STALE_THRESHOLD
        }
    })
