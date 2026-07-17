package no.nav.budstikka.infrastructure.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.AlreadyLoggedWorkerFailure
import no.nav.budstikka.application.LeaseBudgetDrainer
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.MutableClock
import org.slf4j.MDC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant.Companion.fromEpochMilliseconds

class LeaseBudgetDrainerTest :
    FunSpec({
        test("processes every claimed item when the budget holds") {
            with(LeaseBudgetDrainerTestContext()) {
                drainer.drain(
                    leaseDuration = 5.minutes,
                    eventId = { null },
                    claim = { listOf(1, 2, 3) },
                    process = { processed += it },
                )

                processed.shouldContainExactly(1, 2, 3)
            }
        }

        test("stops draining once the lease budget is exhausted") {
            val clock = MutableClock(fromEpochMilliseconds(0))
            with(LeaseBudgetDrainerTestContext(leaseBudgetFraction = 0.1, clock = clock)) {

                drainer.drain(
                    leaseDuration = 1.milliseconds,
                    eventId = { null },
                    claim = {
                        clock.current += 1.milliseconds
                        listOf(1, 2, 3)
                    },
                    process = { processed += it },
                )

                processed.shouldContainExactly()
            }
        }

        test("scopes each item with its eventId in MDC and clears it afterwards") {
            with(LeaseBudgetDrainerTestContext()) {

                drainer.drain(
                    leaseDuration = 5.minutes,
                    eventId = { "event-$it" },
                    claim = { listOf(1, 2) },
                    process = { seen += MDC.get(MdcKeys.EVENT_ID) },
                )

                seen.shouldContainExactly("event-1", "event-2")
                MDC.get(MdcKeys.EVENT_ID) shouldBe null
            }
        }

        test("does not set MDC when eventId is null") {
            with(LeaseBudgetDrainerTestContext()) {

                drainer.drain(
                    leaseDuration = 5.minutes,
                    eventId = { null },
                    claim = { listOf(1) },
                    process = { captureMdcEventId() },
                )

                lastMdcEventId shouldBe null
            }
        }

        test("continues with next item when one item processing fails") {
            with(LeaseBudgetDrainerTestContext()) {
                drainer.drain(
                    leaseDuration = 5.minutes,
                    eventId = { "event-$it" },
                    claim = { listOf(1, 2, 3) },
                    process = {
                        if (it == 2) {
                            throw IllegalStateException("poison row")
                        }
                        processed += it
                    },
                )

                processed.shouldContainExactly(1, 3)
            }
        }

        test("aborts and bubbles when consecutive item failures hit threshold") {
            with(LeaseBudgetDrainerTestContext(maxConsecutiveItemFailures = 2)) {

                val error =
                    shouldThrow<AlreadyLoggedWorkerFailure> {
                        drainer.drain(
                            leaseDuration = 5.minutes,
                            eventId = { "event-$it" },
                            claim = { listOf(1, 2, 3) },
                            process = {
                                if (it <= 2) {
                                    throw IllegalStateException("systemic")
                                }
                                processed += it
                            },
                        )
                    }

                error.rootCause().shouldBeInstanceOf<IllegalStateException>()
                processed.shouldContainExactly()
            }
        }

        test("keeps eventId in MDC across suspension points") {
            with(LeaseBudgetDrainerTestContext()) {
                withContext(MDCContext(mapOf(MdcKeys.WORKER to "inbox-message-worker"))) {
                    drainer.drain(
                        leaseDuration = 5.minutes,
                        eventId = { "event-$it" },
                        claim = { listOf(1, 2) },
                        process = {
                            delay(1.milliseconds)
                            seen += MDC.get(MdcKeys.EVENT_ID)
                        },
                    )
                }

                seen.shouldContainExactly("event-1", "event-2")
            }
        }
    })

private class LeaseBudgetDrainerTestContext(
    leaseBudgetFraction: Double = 0.8,
    maxConsecutiveItemFailures: Int = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
    clock: Clock = Clock.System,
) {
    val drainer =
        LeaseBudgetDrainer(
            leaseBudgetFraction = leaseBudgetFraction,
            maxConsecutiveItemFailures = maxConsecutiveItemFailures,
            clock = clock,
        )

    val processed = mutableListOf<Int>()
    val seen = mutableListOf<String?>()

    var lastMdcEventId: String? = "sentinel"
        private set

    fun captureMdcEventId() {
        lastMdcEventId = MDC.get(MdcKeys.EVENT_ID)
    }
}

private tailrec fun Throwable.rootCause(): Throwable = cause?.takeUnless { it === this }?.rootCause() ?: this
