package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakePersonNameLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import kotlin.time.Clock
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")
private val NOW = Instant.parse("2026-11-02T09:00:00Z")
private val FOUR_MONTHS_LATER = Instant.parse("2027-03-02T09:00:00Z")
private const val CORRELATION_ID = "00000000-0000-0000-0000-000000000a01"
private val NL = Saksmottaker.NarmesteLeder(TEST_LEDERRELASJON_ID, LEADER, TEST_SYKMELDT)
private val NOT_FOUND = ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke")
private val DELETED = ArbeidsgiverNotificationResponse.SakDeleted("DuplikatGrupperingsidEtterDelete")

class ArbeidsgiversakerTest :
    FunSpec({
        test("an existing sak is extended and used without looking up the name") {
            val publisher = FakeArbeidsgiversakPublisher()
            val names = FakePersonNameLookup()

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            publisher.requests shouldBe
                listOf(
                    ArbeidsgiversakExtendRequest(
                        grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                        tag = "Dialogmøte",
                        keepUntil = FOUR_MONTHS_LATER,
                        correlationId = CORRELATION_ID,
                    ),
                )
            names.lookups shouldBe 0
        }

        test("a missing sak is created with the Sykmeldt's name and a link to Dine Sykmeldte, then extended") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)
            val names =
                FakePersonNameLookup().apply { register(TEST_SYKMELDT, PersonName("SYNTETISK", null, "SYKMELDT")) }

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            publisher.sakCreates.single() shouldBe
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    title = "Dialogmøte med Syntetisk Sykmeldt",
                    link = "https://dinesykmeldte.test/sykmeldte/$TEST_LEDERRELASJON_ID",
                    keepUntil = FOUR_MONTHS_LATER,
                    recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT),
                    correlationId = CORRELATION_ID,
                )
            // A concurrent creation is answered as a replay that ignores its deletion time.
            publisher.requests.last() shouldBe
                ArbeidsgiversakExtendRequest("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte", FOUR_MONTHS_LATER, CORRELATION_ID)
        }

        test("the sak lives four months past a later activity, such as a kalenderavtale") {
            val publisher = FakeArbeidsgiversakPublisher()

            saker(publisher).ensure(NL, activityAt = Instant.parse("2026-12-01T09:00:00Z"))

            publisher.sakExtensions.single().keepUntil shouldBe Instant.parse("2027-04-01T08:00:00Z")
        }

        test("the sakstype decides the merkelapp, key and title") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

            saker(publisher).ensure(NL, sakstype = Sakstype.OPPFOLGINGSPLAN) shouldBe
                SakResolution.Ready("OPPFOLGINGSPLAN:$TEST_LEDERRELASJON_ID", "Oppfølging")

            publisher.sakCreates.single().title shouldBe "Oppfølgingsplan"
        }

        test("an Altinn sak is keyed on the resource, the organisation and the producer's sak key, without a link") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)
            val names =
                FakePersonNameLookup().apply { register(TEST_SYKMELDT, PersonName("SYNTETISK", null, "SYKMELDT")) }

            saker(publisher, names).ensure(Saksmottaker.AltinnRessurs("nav_syfo_dialogmote", TEST_SYKMELDT, "dialog-1")) shouldBe
                SakResolution.Ready("DIALOGMOTE:nav_syfo_dialogmote:${TEST_ORGNUMMER.value}:dialog-1", "Dialogmøte")

            publisher.sakCreates.single().apply {
                title shouldBe "Dialogmøte med Syntetisk Sykmeldt"
                link shouldBe null
                recipient shouldBe ArbeidsgiverNotificationRecipient.AltinnRessurs("nav_syfo_dialogmote")
            }
        }

        test("a deleted sak moves on to the next generation of its key") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, DELETED, NOT_FOUND, NOT_FOUND)

            saker(publisher).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")

            publisher.sakCreates.map { it.grupperingsid } shouldBe
                listOf("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "DIALOGMOTE:$TEST_LEDERRELASJON_ID:2")
        }

        test("an existing next generation is found by extending it, without a name lookup or creation") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND)
            val names = FakePersonNameLookup()

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")
            publisher.sakCreates shouldBe emptyList()
            names.lookups shouldBe 0
        }

        test("a rejection is terminal") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))

            saker(publisher).ensure(NL) shouldBe SakResolution.Failed(DeliveryOutcome.Failed("UgyldigMerkelapp"))
        }

        test("a created sak that is not visible yet is retried") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, NOT_FOUND)

            shouldThrow<ChannelHandlerFailure> { saker(publisher).ensure(NL) }
        }

        test("sets the requested status on the sak with the dispatch as idempotency key") {
            val publisher = FakeArbeidsgiversakPublisher()
            val sak = SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            saker(publisher).setStatus(Channel.ARBEIDSGIVERVARSEL, sak, Saksstatus.FERDIG, CORRELATION_ID) shouldBe null

            publisher.saksstatuser.single() shouldBe
                ArbeidsgiversakStatusRequest("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte", Saksstatus.FERDIG, CORRELATION_ID)
            saker(FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("Konflikt")))
                .setStatus(Channel.ARBEIDSGIVERVARSEL, sak, Saksstatus.FERDIG, CORRELATION_ID) shouldBe DeliveryOutcome.Failed("Konflikt")
        }

        test("titles are capitalised per name part and capped at 140 characters") {
            Sakstype.DIALOGMOTE.title(PersonName("ANNE-MARIE", "von", "NORDMANN")) shouldBe "Dialogmøte med Anne-Marie Von Nordmann"
            Sakstype.DIALOGMOTE.title(null) shouldBe "Dialogmøte"
            Sakstype.OPPFOLGINGSPLAN.title(PersonName("SYNTETISK", null, "SYKMELDT")) shouldBe "Oppfølgingsplan for Syntetisk Sykmeldt"
            Sakstype.DIALOGMOTE.title(PersonName("A".repeat(200), null, "B")) shouldHaveLength 140
            PersonName("SYNTETISK", null, "SYKMELDT").toString() shouldNotContain "SYNTETISK"
        }

        test("an Altinn sak key has no separators and is never a personident") {
            Saksmottaker.AltinnRessurs.isValidSakId("4711", TEST_SYKMELDT) shouldBe true
            Saksmottaker.AltinnRessurs.isValidSakId("0f8fad5b-d9cb-469f-a165-a1b2c3d4e5f6", TEST_SYKMELDT) shouldBe true
            listOf("", "dialog:2", "dialog 2", "x".repeat(65), "1".repeat(11), "dialog-${TEST_SYKMELDT.value}").forEach {
                Saksmottaker.AltinnRessurs.isValidSakId(it, TEST_SYKMELDT) shouldBe false
            }
        }
    })

private val CONFIG =
    ArbeidsgiversakConfig(
        dineSykmeldteUrl = "https://dinesykmeldte.test/sykmeldte/",
    )

private fun saker(
    publisher: FakeArbeidsgiversakPublisher,
    names: FakePersonNameLookup = FakePersonNameLookup(),
) = Arbeidsgiversaker(
    publisher = publisher,
    personNames = names,
    config = CONFIG,
    clock =
        object : Clock {
            override fun now(): Instant = NOW
        },
)

private suspend fun Arbeidsgiversaker.ensure(
    mottaker: Saksmottaker,
    sakstype: Sakstype = Sakstype.DIALOGMOTE,
    activityAt: Instant? = null,
) = ensure(
    channel = Channel.ARBEIDSGIVERVARSEL,
    sakstype = sakstype,
    virksomhetsnummer = TEST_ORGNUMMER.value,
    mottaker = mottaker,
    correlationId = CORRELATION_ID,
    activityAt = activityAt,
)
