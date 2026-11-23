package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchHeader
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.testsupport.BudstikkaTestApp
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Tags("E2E")
class ScheduledMicrofrontendDisableE2ESpec :
    FunSpec({
        test("an enable with visibleUntil reaches Min side as an enable and after visibleUntil as a disable") {
            BudstikkaTestApp.start().use { app ->
                val microfrontendId = "e2e-microfrontend-${UUID.randomUUID()}"
                val enable =
                    MicrofrontendEnable(
                        personIdentifier = TEST_SYKMELDT,
                        microfrontendId = microfrontendId,
                        visibleUntil = Clock.System.now() + 30.seconds,
                    )
                app.produce(
                    topic = app.budstikkaTopic,
                    key = enable.partitionKey,
                    value = dispatchJson.encodeToString(Dispatch("e2e-scheduled-disable", enable)),
                    headers = mapOf(DispatchHeader.EVENT_ID to UUID.randomUUID().toString()),
                )

                eventually(120.seconds) {
                    val actions =
                        app
                            .consumeRecords(app.minsideMicrofrontendTopic)
                            .filter { it.key() == TEST_SYKMELDT.value }
                            .map { Json.parseToJsonElement(it.value()).jsonObject }
                            .filter { it["microfrontend_id"]?.jsonPrimitive?.content == microfrontendId }
                            .map { it.getValue("@action").jsonPrimitive.content }
                    actions shouldContainExactly listOf("enable", "disable")
                }
            }
        }
    })
