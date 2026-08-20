package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.BrevCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.deadLookupFor

class DeathGateTest :
    FunSpec({
        fun envelope(content: DispatchContent) = Dispatch(reference = "ref-1", content = content)

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
            test("Sykmeldt-directed CREATE ($name) for dead Sykmeldt is dropped with DEAD") {
                val gate = DeathGate(deadLookupFor(TEST_SYKMELDT))
                gate.decide(content) shouldBe Decision.Dropped(DropReason.DEAD)
            }
        }

        test("living Sykmeldt-directed CREATE passes Deliveries through unchanged") {
            val content = BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")
            DeathGate(FakeDeathLookup()).decide(content).shouldBeInstanceOf<Decision.Processed>()
        }

        test("Inactivate is not gated even when the Sykmeldt is dead") {
            val content = BrukervarselInactivate(reference = "ref-1", sykmeldt = TEST_SYKMELDT)
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(content)
                .shouldBeInstanceOf<Decision.Processed>()
        }

        test("Microfrontend enable/disable is not gated even when the Sykmeldt is dead") {
            val gate = DeathGate(deadLookupFor(TEST_SYKMELDT))
            gate.decide(MicrofrontendEnable(TEST_SYKMELDT, "mf-1")).shouldBeInstanceOf<Decision.Processed>()
            gate.decide(MicrofrontendDisable(TEST_SYKMELDT, "mf-1")).shouldBeInstanceOf<Decision.Processed>()
        }

        test("leader notification is not gated on the employee's death (recipient is the leader)") {
            val content =
                LedervarselCreate(
                    sykmeldt = TEST_SYKMELDT,
                    orgnummer = TEST_ORGNUMMER,
                    oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                    text = "text",
                )
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(content)
                .shouldBeInstanceOf<Decision.Processed>()
        }

        test("Arbeidsgivervarsel (Altinn) has no Sykmeldt to look up") {
            val ag =
                ArbeidsgivervarselCreate(
                    orgnummer = TEST_ORGNUMMER,
                    recipient = AltinnResource("nav_syfo_dialogmote"),
                    tag = "Oppfølging",
                    text = "t",
                    link = "https://nav.no",
                )
            DeathGate(deadLookupFor(TEST_SYKMELDT))
                .decide(ag)
                .shouldBeInstanceOf<Decision.Processed>()
        }
    })
