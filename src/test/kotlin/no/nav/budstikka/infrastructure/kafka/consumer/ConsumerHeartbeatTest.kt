package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class ConsumerHeartbeatTest :
    FunSpec({
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val threshold = Duration.ofMinutes(5)

        test("is alive immediately after construction") {
            val heartbeat = ConsumerHeartbeat(MutableClock(start), threshold)

            heartbeat.isAlive() shouldBe true
        }

        test("stays alive at exactly the threshold since the last poll") {
            val clock = MutableClock(start)
            val heartbeat = ConsumerHeartbeat(clock, threshold)

            heartbeat.recordPoll()
            clock.current = start.plus(threshold)

            heartbeat.isAlive() shouldBe true
        }

        test("reports stale once the threshold is exceeded since the last poll") {
            val clock = MutableClock(start)
            val heartbeat = ConsumerHeartbeat(clock, threshold)

            heartbeat.recordPoll()
            clock.current = start.plus(threshold).plusSeconds(1)

            heartbeat.isAlive() shouldBe false
        }

        test("recording a poll refreshes liveness") {
            val clock = MutableClock(start)
            val heartbeat = ConsumerHeartbeat(clock, threshold)

            clock.current = start.plus(Duration.ofMinutes(10))
            heartbeat.isAlive() shouldBe false

            heartbeat.recordPoll()
            heartbeat.isAlive() shouldBe true
        }
    })
