package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import java.util.UUID

class DecisionProcessTest :
    FunSpec({
        val sykmeldt = PersonIdentifier("11111111111")

        fun event() =
            Dispatch(
                eventId = UUID.randomUUID(),
                reference = "ref-1",
                content = BrukervarselCreate(sykmeldt, Varseltype.OPPGAVE, "text"),
            )

        test("dead person -> Dropped(DEAD) end-to-end via death lookup") {
            val fake = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val process = DecisionProcess(IsAliveDecisionPolicy(fake), UnrestrictedDecisionPolicy)
            process.process(event()) shouldBe Decision.Dropped(DropReason.DEAD)
        }

        test("alive person -> Processed with one delivery") {
            val process = DecisionProcess(IsAliveDecisionPolicy(FakeDeathLookup()), UnrestrictedDecisionPolicy)
            process.process(event()).shouldBeInstanceOf<Decision.Processed>()
        }
    })
