package no.nav.budstikka.domain.decision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.BrevFallback
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.Oppgavetype
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.deadLookupFor
import no.nav.budstikka.fakes.reservedLookupFor

class DecisionProcessTest :
    FunSpec({
        fun processWith(deathLookup: FakeDeathLookup) = DecisionProcess(listOf(DeathGate(deathLookup)))

        fun event(content: DispatchContent) = Dispatch(reference = "ref-1", content = content)

        test("Sykmeldt-directed CREATE for dead Sykmeldt -> Dropped(DEAD) through DeathGate") {
            val decision =
                processWith(
                    deadLookupFor(TEST_SYKMELDT),
                ).process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision shouldBe Decision.Dropped(DropReason.DEAD)
        }

        test("living Sykmeldt -> Processed with one Delivery") {
            val decision =
                processWith(FakeDeathLookup())
                    .process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("Microfrontend has no applicable gate -> Processed even when the Sykmeldt is dead") {
            val decision = processWith(deadLookupFor(TEST_SYKMELDT)).process(event(MicrofrontendEnable(TEST_SYKMELDT, "mf-1")))
            decision.shouldBeInstanceOf<Decision.Processed>()
        }

        test("empty rule list -> always Processed (no gate)") {
            val decision =
                DecisionProcess(emptyList())
                    .process(event(BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "text")))
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 1
        }

        test("Ledervarsel is not gated on the Sykmeldt's death; future Recipient is Nærmeste leder") {
            val decision =
                processWith(deadLookupFor(TEST_SYKMELDT)).process(
                    event(
                        LedervarselCreate(
                            TEST_SYKMELDT,
                            TEST_ORGNUMMER,
                            Oppgavetype.DIALOGMOTE_INNKALLING,
                            "text",
                        ),
                    ),
                )
            decision.shouldBeInstanceOf<Decision.Processed>()
        }

        test("DeathGate precedes ReservationGate: dead Sykmeldt + Reservasjon + BrevFallback -> Dropped") {
            val decision =
                DecisionProcess(
                    listOf(
                        DeathGate(deadLookupFor(TEST_SYKMELDT)),
                        ReservationGate(reservedLookupFor(TEST_SYKMELDT)),
                    ),
                ).process(
                    event(
                        BrukervarselCreate(
                            TEST_SYKMELDT,
                            Varseltype.OPPGAVE,
                            "text",
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                        ),
                    ),
                )
            decision shouldBe Decision.Dropped(DropReason.DEAD)
        }

        test("living Sykmeldt + Reservasjon + BrevFallback -> Processed with in-app + BREV") {
            val decision =
                DecisionProcess(
                    listOf(
                        DeathGate(FakeDeathLookup()),
                        ReservationGate(reservedLookupFor(TEST_SYKMELDT)),
                    ),
                ).process(
                    event(
                        BrukervarselCreate(
                            TEST_SYKMELDT,
                            Varseltype.OPPGAVE,
                            "text",
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                        ),
                    ),
                )
            decision.shouldBeInstanceOf<Decision.Processed>().deliveries shouldHaveSize 2
        }
    })
