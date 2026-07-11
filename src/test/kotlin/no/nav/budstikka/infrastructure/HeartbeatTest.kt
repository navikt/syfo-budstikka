package no.nav.budstikka.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class HeartbeatTest :
    FunSpec({
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val threshold = Duration.ofMinutes(5)

        test("is alive immediately after construction") {
            val heartbeat = Heartbeat(MutableClock(start), threshold)

            heartbeat.isAlive() shouldBe true
        }

        test("stays alive at exactly the threshold since the last beat") {
            val clock = MutableClock(start)
            val heartbeat = Heartbeat(clock, threshold)

            heartbeat.record()
            clock.current = start.plus(threshold)

            heartbeat.isAlive() shouldBe true
        }

        test("reports stale once the threshold is exceeded since the last beat") {
            val clock = MutableClock(start)
            val heartbeat = Heartbeat(clock, threshold)

            heartbeat.record()
            clock.current = start.plus(threshold).plusSeconds(1)

            heartbeat.isAlive() shouldBe false
        }

        test("recording a beat refreshes liveness") {
            val clock = MutableClock(start)
            val heartbeat = Heartbeat(clock, threshold)

            clock.current = start.plus(Duration.ofMinutes(10))
            heartbeat.isAlive() shouldBe false

            heartbeat.record()
            heartbeat.isAlive() shouldBe true
        }
    })
