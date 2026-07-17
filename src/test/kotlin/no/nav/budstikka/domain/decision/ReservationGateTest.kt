package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrevFallback
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.ExternalVarsling
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.Oppgavetype
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeReservationLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.reservedLookupFor

class ReservationGateTest :
    FunSpec({
        fun event(content: DispatchContent) = Dispatch(reference = "ref-1", content = content)

        suspend fun decide(
            lookup: FakeReservationLookup,
            content: DispatchContent,
        ): Decision = DecisionProcess(listOf(ReservationGate(lookup))).process(event(content))

        val externalVarsling = ExternalVarsling(smsText = "Du har et nytt varsel")
        val brevFallback = BrevFallback(journalpostId = "jp-1")

        test("Reservasjon + ekstern varsling + BrevFallback -> in-app (no ekstern varsling) + BREV") {
            val decision =
                decide(
                    reservedLookupFor(TEST_SYKMELDT),
                    BrukervarselCreate(
                        TEST_SYKMELDT,
                        Varseltype.OPPGAVE,
                        "text",
                        externalVarsling = externalVarsling,
                        brevFallback = brevFallback,
                    ),
                )

            val deliveries = decision.shouldBeInstanceOf<Decision.Processed>().deliveries
            deliveries shouldHaveSize 2

            val brukervarsel = deliveries.single { it.channel == Channel.BRUKERVARSEL }
            (brukervarsel.content as BrukervarselCreate).externalVarsling shouldBe null

            val brev = deliveries.single { it.channel == Channel.BREV }
            brev.operation shouldBe Operation.CREATE
            (brev.content as BrevCreate).journalpostId shouldBe "jp-1"
            (brev.content as BrevCreate).personIdentifier shouldBe TEST_SYKMELDT
        }

        test("Reservasjon + ekstern varsling, no BrevFallback -> in-app only") {
            val decision =
                decide(
                    reservedLookupFor(TEST_SYKMELDT),
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "text", externalVarsling = externalVarsling),
                )

            val deliveries = decision.shouldBeInstanceOf<Decision.Processed>().deliveries
            deliveries shouldHaveSize 1
            deliveries.single().channel shouldBe Channel.BRUKERVARSEL
            (deliveries.single().content as BrukervarselCreate).externalVarsling shouldBe null
        }

        test("Reservasjon + BrevFallback, no ekstern varsling -> in-app + BREV") {
            val decision =
                decide(
                    reservedLookupFor(TEST_SYKMELDT),
                    BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text", brevFallback = brevFallback),
                )

            val deliveries = decision.shouldBeInstanceOf<Decision.Processed>().deliveries
            deliveries shouldHaveSize 2
            deliveries.singleOrNull { it.channel == Channel.BREV }.shouldNotBeNull()
        }

        test("no Reservasjon -> ekstern varsling kept, no BREV") {
            val decision =
                decide(
                    FakeReservationLookup(),
                    BrukervarselCreate(
                        TEST_SYKMELDT,
                        Varseltype.OPPGAVE,
                        "text",
                        externalVarsling = externalVarsling,
                        brevFallback = brevFallback,
                    ),
                )

            val deliveries = decision.shouldBeInstanceOf<Decision.Processed>().deliveries
            deliveries shouldHaveSize 1
            deliveries.single().channel shouldBe Channel.BRUKERVARSEL
            (deliveries.single().content as BrukervarselCreate).externalVarsling shouldBe externalVarsling
        }

        test("Brukervarsel with neither ekstern varsling nor BrevFallback -> no KRR lookup") {
            val lookup = FakeReservationLookup().apply { registerReserved(TEST_SYKMELDT) }
            val decision = decide(lookup, BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "text"))

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
            lookup.lookupCount shouldBe 0
        }

        test("non-Brukervarsel Dispatch -> no KRR lookup, unchanged") {
            val lookup = FakeReservationLookup().apply { registerReserved(TEST_SYKMELDT) }
            val decision =
                decide(
                    lookup,
                    LedervarselCreate(TEST_SYKMELDT, TEST_ORGNUMMER, Oppgavetype.DIALOGMOTE_INNKALLING, "text"),
                )

            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
            lookup.lookupCount shouldBe 0
        }
    })
