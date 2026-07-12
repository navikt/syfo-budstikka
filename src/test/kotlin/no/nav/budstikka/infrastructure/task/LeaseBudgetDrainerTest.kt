package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.MdcKeys
import org.slf4j.MDC
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaseBudgetDrainerTest :
    FunSpec({
        test("processes every claimed item when the budget holds") {
            val processed = mutableListOf<Int>()
            val drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8)

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
            val drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.1)

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
            val drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8)

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
            val drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8)

            drainer.drain(
                leaseDuration = Duration.ofMinutes(5),
                eventId = { null },
                claim = { listOf(1) },
                process = { sawKey = MDC.get(MdcKeys.EVENT_ID) },
            )

            sawKey shouldBe null
        }

        test("keeps eventId in MDC across suspension points") {
            val seen = mutableListOf<String?>()
            val drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8)

            withContext(MDCContext(mapOf(MdcKeys.TASK to "inbox-message-task"))) {
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
