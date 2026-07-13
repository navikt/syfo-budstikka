package no.nav.budstikka.infrastructure.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.LeaseBudgetDrainer
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.application.MdcKeys
import org.slf4j.MDC
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaseBudgetDrainerTest :
    FunSpec({
        test("processes every claimed item when the budget holds") {
            val processed = mutableListOf<Int>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            drainer.drain(
                leaseDuration = Duration.ofMinutes(5),
                eventId = { null },
                claim = { listOf(1, 2, 3) },
                process = { processed += it },
            )

            processed.shouldContainExactly(1, 2, 3)
        }

        test("stops draining once the lease budget is exhausted") {
            val processed = mutableListOf<Int>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.1,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            drainer.drain(
                leaseDuration = Duration.ofMillis(1),
                eventId = { null },
                claim = { listOf(1, 2, 3) },
                process = { processed += it },
            )

            processed.shouldContainExactly()
        }

        test("scopes each item with its eventId in MDC and clears it afterwards") {
            val seen = mutableListOf<String?>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            drainer.drain(
                leaseDuration = Duration.ofMinutes(5),
                eventId = { "event-$it" },
                claim = { listOf(1, 2) },
                process = { seen += MDC.get(MdcKeys.EVENT_ID) },
            )

            seen.shouldContainExactly("event-1", "event-2")
            MDC.get(MdcKeys.EVENT_ID) shouldBe null
        }

        test("does not set MDC when eventId is null") {
            var sawKey: String? = "sentinel"
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            drainer.drain(
                leaseDuration = Duration.ofMinutes(5),
                eventId = { null },
                claim = { listOf(1) },
                process = { sawKey = MDC.get(MdcKeys.EVENT_ID) },
            )

            sawKey shouldBe null
        }

        test("continues with next item when one item processing fails") {
            val processed = mutableListOf<Int>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            drainer.drain(
                leaseDuration = Duration.ofMinutes(5),
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

        test("aborts and bubbles when consecutive item failures hit threshold") {
            val processed = mutableListOf<Int>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = 2,
                )

            shouldThrow<IllegalStateException> {
                drainer.drain(
                    leaseDuration = Duration.ofMinutes(5),
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

            processed.shouldContainExactly()
        }

        test("keeps eventId in MDC across suspension points") {
            val seen = mutableListOf<String?>()
            val drainer =
                LeaseBudgetDrainer(
                    leaseBudgetFraction = 0.8,
                    maxConsecutiveItemFailures = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
                )

            withContext(MDCContext(mapOf(MdcKeys.WORKER to "inbox-message-worker"))) {
                drainer.drain(
                    leaseDuration = Duration.ofMinutes(5),
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
    })
