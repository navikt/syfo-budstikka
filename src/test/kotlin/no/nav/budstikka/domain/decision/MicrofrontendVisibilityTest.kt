package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MICROFRONTEND_ID = "syfo-dialog"
private val NOW = Instant.parse("2026-11-16T12:00:00Z")

class MicrofrontendVisibilityTest :
    FunSpec({
        fun enable(visibleUntil: Instant? = null) = MicrofrontendEnable(TEST_SYKMELDT, MICROFRONTEND_ID, visibleUntil)

        fun disable() = MicrofrontendDisable(TEST_SYKMELDT, MICROFRONTEND_ID)

        fun processed(content: DispatchContent) = Decision.Processed(listOf(content.toDeliveryDraft("ref-0")))

        suspend fun decide(content: DispatchContent): Decision.Processed =
            DecisionProcess(listOf(PassedVisibleUntilRule(MutableClock(NOW))))
                .process(Dispatch("ref", content))
                .shouldBeInstanceOf<Decision.Processed>()

        context("PassedVisibleUntilRule") {
            listOf(
                "has passed" to NOW - 1.seconds,
                "is now" to NOW,
            ).forEach { (name, visibleUntil) ->
                test("decides an enable whose visibleUntil $name as an immediate disable") {
                    val draft = decide(enable(visibleUntil)).deliveries.single()

                    draft.operation shouldBe Operation.INACTIVATE
                    draft.content shouldBe disable()
                    draft.channel shouldBe Channel.MICROFRONTEND
                }
            }

            listOf(
                "an enable with a future visibleUntil" to enable(NOW + 1.seconds),
                "an enable without visibleUntil" to enable(),
                "a disable" to disable(),
            ).forEach { (name, content) ->
                test("leaves $name unchanged") {
                    decide(content).deliveries.single() shouldBe content.toDeliveryDraft("ref")
                }
            }
        }

        context("scheduledDispatch") {
            test("schedules the disable for the visibleUntil of an enable") {
                val visibleUntil = NOW + 1.seconds

                processed(enable(visibleUntil)).scheduledDispatch() shouldBe
                    ScheduledDispatch(
                        reference = "ref-0",
                        content = disable(),
                        notBefore = visibleUntil,
                        waitReason = SCHEDULED_DISABLE_WAIT_REASON,
                    )
            }

            test("schedules nothing for an enable without visibleUntil or for a disable") {
                processed(enable()).scheduledDispatch().shouldBeNull()
                processed(disable()).scheduledDispatch().shouldBeNull()
            }

            test("schedules nothing once the rule has turned a passed enable into a disable") {
                decide(enable(NOW - 1.seconds)).scheduledDispatch().shouldBeNull()
            }
        }
    })
