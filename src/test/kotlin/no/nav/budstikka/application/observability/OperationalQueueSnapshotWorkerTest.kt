package no.nav.budstikka.application.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.MutableClock
import kotlin.time.Instant

class OperationalQueueSnapshotWorkerTest :
    FunSpec({
        test("records a snapshot observed at the start of the iteration") {
            val observedAt = Instant.parse("2026-08-30T12:00:00Z")
            val clock = MutableClock(observedAt)
            var requestedAt: Instant? = null
            var recorded: OperationalQueueSnapshot? = null
            val snapshot =
                OperationalQueueSnapshot(
                    observedAt = observedAt,
                    inbox = emptyMap(),
                    deliveries = emptyMap(),
                )
            val worker =
                OperationalQueueSnapshotWorker(
                    repository = { at ->
                        requestedAt = at
                        snapshot
                    },
                    metrics = { recorded = it },
                    clock = clock,
                )

            worker.runOnce()

            requestedAt shouldBe observedAt
            recorded shouldBe snapshot
        }
    })
