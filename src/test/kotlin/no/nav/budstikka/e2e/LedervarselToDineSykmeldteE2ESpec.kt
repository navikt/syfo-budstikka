package no.nav.budstikka.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.Oppgavetype
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.testsupport.BudstikkaTestApp
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Ende-til-ende-bevis for LEDERVARSEL-kanalen (#108, B62): booter HELE appen mot Testcontainers,
 * produserer en `Dispatch(LedervarselCreate)` til budstikka-topicet, og asserter at hele løpet
 * (inbox → decide → outbox → LedervarselChannelHandler → producer) ender med en `OpprettHendelse`
 * på `dinesykmeldte-hendelser-v2` med korrekt `oppgavetype`/`ansattFnr` og Kafka-key = `reference`.
 * Ingen ekstern side-effekt (kanalen er rent in-app). Tagget `E2E` → kjøres via `./gradlew e2eTest`.
 */
@Tags("E2E")
class LedervarselToDineSykmeldteE2ESpec :
    FunSpec({
        test("produced LedervarselCreate is published as OpprettHendelse on dinesykmeldte-hendelser-v2") {
            BudstikkaTestApp.start().use { app ->
                val eventId = UUID.randomUUID()
                val reference = "e2e-ledervarsel-${UUID.randomUUID()}"
                val ansattFnr = "12345678901"
                val orgnummer = "987654321"
                val dispatch =
                    Dispatch(
                        eventId = eventId,
                        reference = reference,
                        content =
                            LedervarselCreate(
                                sykmeldt = PersonIdentifier(ansattFnr),
                                orgnummer = Orgnummer(orgnummer),
                                oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                                text = "Din ansatte er innkalt til dialogmøte",
                                link = "https://nav.no/dm/1",
                            ),
                    )

                app.produce(
                    topic = app.budstikkaTopic,
                    key = dispatch.content.partitionKey,
                    value = dispatchJson.encodeToString(dispatch),
                    headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
                )

                eventually(30.seconds) {
                    val record =
                        app
                            .consumeRecords(app.dineSykmeldteTopic)
                            .singleOrNull { it.key() == reference }
                    record.shouldNotBeNull()

                    val hendelse = Json.parseToJsonElement(record.value()).jsonObject
                    hendelse["id"]!!.jsonPrimitive.content shouldBe reference

                    val opprett = hendelse["opprettHendelse"]!!.jsonObject
                    opprett["ansattFnr"]!!.jsonPrimitive.content shouldBe ansattFnr
                    opprett["orgnummer"]!!.jsonPrimitive.content shouldBe orgnummer
                    opprett["oppgavetype"]!!.jsonPrimitive.content shouldBe "DIALOGMOTE_INNKALLING"
                }
            }
        }
    })
