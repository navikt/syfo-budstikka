package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchHeader
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.testsupport.BudstikkaTestApp
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@Tags("E2E")
class DittSykefravaerE2ESpec :
    FunSpec({
        test("create and inactivate reach Flex's topic with the stable reference as key") {
            BudstikkaTestApp.start().use { app ->
                val reference = "e2e-ditt-sykefravaer-${UUID.randomUUID()}"
                listOf(
                    DittSykefravaerCreate(
                        personIdentifier = TEST_SYKMELDT,
                        text = "Ny innkalling",
                        messageType = "DIALOGMOTE_INNKALLING",
                    ),
                    DittSykefravaerInactivate(reference, TEST_SYKMELDT),
                ).forEach { content ->
                    app.produce(
                        topic = app.budstikkaTopic,
                        key = content.partitionKey,
                        value = dispatchJson.encodeToString(Dispatch(reference, content)),
                        headers = mapOf(DispatchHeader.EVENT_ID to UUID.randomUUID().toString()),
                    )
                }

                eventually(30.seconds) {
                    val records = app.consumeRecords(app.dittSykefravaerTopic).filter { it.key() == reference }
                    records shouldHaveSize 2

                    val create = Json.parseToJsonElement(records[0].value()).jsonObject
                    create["fnr"]!!.jsonPrimitive.content shouldBe TEST_SYKMELDT.value
                    create["opprettMelding"]!!.jsonObject["meldingType"]!!.jsonPrimitive.content shouldBe
                        "DIALOGMOTE_INNKALLING"

                    val inactivate = Json.parseToJsonElement(records[1].value()).jsonObject
                    inactivate["opprettMelding"]!!.toString() shouldBe "null"
                    inactivate["lukkMelding"]!!
                        .jsonObject["timestamp"]!!
                        .jsonPrimitive.content
                        .isNotBlank() shouldBe true
                }
            }
        }
    })
