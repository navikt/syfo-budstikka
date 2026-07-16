package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.deadLookupFor
import java.util.UUID

class DecisionProcessTest :
    FunSpec({
        fun processWith(deathLookup: FakeDeathLookup) = DecisionProcess(listOf(DeathGate(deathLookup)))

        fun event(content: DispatchContent) = Dispatch(eventId = UUID.randomUUID(), reference = "ref-1", content = content)

        test("user-facing CREATE for dead person -> Dropped(DEAD) end-to-end via death gate") {
            val decision =
                processWith(
                    deadLookupFor(TEST_SYKMELDT),
                ).process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision shouldBe Decision.Dropped(DropReason.DEAD)
        }

        test("!alive person -> Processed with one delivery") {
            val decision =
                processWith(FakeDeathLookup())
                    .process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("!microfrontend has no applicable gate -> Processed even when the person is dead") {
            val decision =
                processWith(deadLookupFor(TEST_SYKMELDT)).process(event(MicrofrontendEnable(TEST_SYKMELDT, "mf-1")))
            decision.shouldBeInstanceOf<Decision.Processed>()
        }

        test("empty rule list -> always Processed (no gate)") {
            val decision =
                DecisionProcess(emptyList())
                    .process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("!leader notification is not gated on the employee's death (recipient is the leader)") {
            val decision =
                processWith(deadLookupFor(TEST_SYKMELDT)).process(
                    event(
                        LedervarselCreate(
                            TEST_SYKMELDT,
                            TEST_ORGNUMMER,
                            "text",
                        ),
                    ),
                )
            decision.shouldBeInstanceOf<Decision.Processed>()
        }
    })
