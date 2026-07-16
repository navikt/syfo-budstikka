package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.AltinnResource
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.DittSykefravaerCreate
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.deadLookupFor
import java.util.UUID

/**
 * Død-gaten i isolasjon: den self-selekterer på [gatedPerson] og henter PDL kun når hendelsen kan
 * gates. Kjede-effekten (short-circuit, e2e) dekkes av [DecisionProcessTest].
 */
class DeathGateTest :
    FunSpec({
        fun envelope(content: DispatchContent) =
            Dispatch(eventId = UUID.randomUUID(), reference = "ref-1", content = content)

        suspend fun DeathGate.decide(content: DispatchContent): Decision {
            val event = envelope(content)
            return resolve(event).apply(listOf(content.toDeliveryDraft(event.reference)))
        }

        val gatedCreates =
            listOf(
                "Brukervarsel" to BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text"),
                "DittSykefravaer" to DittSykefravaerCreate(TEST_SYKMELDT, "text"),
                "Brev" to BrevCreate(TEST_SYKMELDT, "jp-1"),
            )

        gatedCreates.forEach { (name, content) ->
            test("user-facing CREATE ($name) for dead person is dropped with DEAD") {
                val gate = DeathGate(deadLookupFor(TEST_SYKMELDT))
                gate.decide(content) shouldBe Decision.Dropped(DropReason.DEAD)
            }
        }

        test("!alive user-facing person passes deliveries through unchanged") {
            val content = BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")
            DeathGate(FakeDeathLookup()).decide(content).shouldBeInstanceOf<Decision.Processed>()
        }

        test("!close operation (INACTIVATE) is not gated even when recipient is dead") {
            val content = BrukervarselInactivate(reference = "ref-1", sykmeldt = TEST_SYKMELDT)
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(content)
                .shouldBeInstanceOf<Decision.Processed>()
        }

        test("!microfrontend enable/disable is not gated even when recipient is dead") {
            val gate = DeathGate(deadLookupFor(TEST_SYKMELDT))
            gate.decide(MicrofrontendEnable(TEST_SYKMELDT, "mf-1")).shouldBeInstanceOf<Decision.Processed>()
            gate.decide(MicrofrontendDisable(TEST_SYKMELDT, "mf-1")).shouldBeInstanceOf<Decision.Processed>()
        }

        test("!leader notification is not gated on the employee's death (recipient is the leader)") {
            val content = LedervarselCreate(sykmeldt = TEST_SYKMELDT, orgnummer = TEST_ORGNUMMER, text = "text")
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(content)
                .shouldBeInstanceOf<Decision.Processed>()
        }

        test("!employer notification (Altinn) has no person to look up") {
            val ag =
                ArbeidsgivervarselCreate(
                    orgnummer = TEST_ORGNUMMER,
                    recipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
                    tag = Tag.OPPFOELGING,
                    text = "t",
                    link = "https://nav.no",
                )
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(ag)
                .shouldBeInstanceOf<Decision.Processed>()
        }
    })
