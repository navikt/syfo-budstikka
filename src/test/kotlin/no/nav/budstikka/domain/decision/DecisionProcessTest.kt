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
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import java.util.UUID

class DecisionProcessTest :
    FunSpec({
        val sykmeldt = PersonIdentifier("11111111111")
        val orgnr = Orgnummer("987654321")

        fun processWith(deathLookup: FakeDeathLookup) = DecisionProcess(listOf(DeathGate(deathLookup)))

        fun event(content: DispatchContent) = Dispatch(eventId = UUID.randomUUID(), reference = "ref-1", content = content)

        test("user-facing CREATE for dead person -> Dropped(DEAD) end-to-end via death gate") {
            val deathLookup = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val decision = processWith(deathLookup).process(event(BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text")))
            decision shouldBe Decision.Dropped(DropReason.DEAD)
        }

        test("alive person -> Processed with one delivery") {
            val decision =
                processWith(FakeDeathLookup())
                    .process(event(BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("microfrontend has no applicable gate -> Processed even when the person is dead") {
            val deathLookup = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val decision = processWith(deathLookup).process(event(MicrofrontendEnable(sykmeldt, "mf-1")))
            decision.shouldBeInstanceOf<Decision.Processed>()
        }

        test("empty rule list -> always Processed (no gate)") {
            val decision =
                DecisionProcess(emptyList())
                    .process(event(BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("leader notification is not gated on the employee's death (recipient is the leader)") {
            val deathLookup = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val decision = processWith(deathLookup).process(event(LedervarselCreate(sykmeldt, orgnr, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>()
        }
    })
