package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.dispatch.AltinnResource
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import java.util.UUID

class FoundationFetcherTest :
    FunSpec({
        val sykmeldt = PersonIdentifier("11111111111")
        val orgnr = Orgnummer("987654321")

        fun envelope(content: DispatchContent) = Dispatch(eventId = UUID.randomUUID(), reference = "ref-1", content = content)

        test("dead user-facing person yields recipientIsDead=true") {
            val fake = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val foundation = FoundationFetcher(fake).fetch(envelope(BrukervarselCreate(sykmeldt, Varseltype.BESKJED, "t")))
            foundation shouldBe DecisionFoundation(recipientIsDead = true)
        }

        test("alive user-facing person yields recipientIsDead=false") {
            val foundation = FoundationFetcher(FakeDeathLookup()).fetch(envelope(BrukervarselCreate(sykmeldt, Varseltype.BESKJED, "t")))
            foundation shouldBe DecisionFoundation(recipientIsDead = false)
        }

        test("leader notification does not look up death for the employee (no gate) even when marked dead") {
            val fake = FakeDeathLookup().apply { registerDeath(sykmeldt) }
            val foundation = FoundationFetcher(fake).fetch(envelope(LedervarselCreate(sykmeldt, orgnr, "t")))
            foundation shouldBe DecisionFoundation(recipientIsDead = false)
        }

        test("employer notification (Altinn) has no person to look up") {
            val ag =
                ArbeidsgivervarselCreate(
                    orgnummer = orgnr,
                    recipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
                    tag = Tag.OPPFOELGING,
                    text = "t",
                    link = "https://nav.no",
                )
            val foundation = FoundationFetcher(FakeDeathLookup().apply { registerDeath(sykmeldt) }).fetch(envelope(ag))
            foundation shouldBe DecisionFoundation(recipientIsDead = false)
        }
    })
