package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Saktype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakePersonnavnLookup
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import kotlin.time.Clock
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")
private val NOW = Instant.parse("2026-11-02T09:00:00Z")
private val FOUR_MONTHS_LATER = Instant.parse("2027-03-02T09:00:00Z")
private const val CORRELATION_ID = "00000000-0000-0000-0000-000000000a01"
private val NL = Saksmottaker.NarmesteLeder(TEST_LEDERRELASJON_ID, LEADER, TEST_SYKMELDT)
private val NOT_FOUND = ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke")
private val DELETED = ArbeidsgiverNotificationResponse.CaseDeleted("DuplikatGrupperingsidEtterDelete")

class ArbeidsgiversakerTest :
    FunSpec({
        test("an existing case is extended and used without looking up the name") {
            val publisher = FakeArbeidsgiversakPublisher()
            val names = FakePersonnavnLookup()

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

        test("a missing case is created with the Sykmeldt's name and a link to Dine Sykmeldte, then extended") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)
            val names =
                FakePersonnavnLookup().apply { register(TEST_SYKMELDT, Personnavn("SYNTETISK", null, "SYKMELDT")) }

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            publisher.caseCreates.single() shouldBe
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    tittel = "Dialogmøte med Syntetisk Sykmeldt",
                    lenke = "https://dinesykmeldte.test/sykmeldte/$TEST_LEDERRELASJON_ID",
                    keepUntil = FOUR_MONTHS_LATER,
                    recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT),
                    correlationId = CORRELATION_ID,
                )
            // A concurrent creation is answered as a replay that ignores its deletion time.
            publisher.requests.last() shouldBe
                ArbeidsgiversakExtendRequest("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte", FOUR_MONTHS_LATER, CORRELATION_ID)
        }

        test("the case lives four months past a later activity, such as a calendar event") {
            val publisher = FakeArbeidsgiversakPublisher()

            saker(publisher).ensure(NL, activityAt = Instant.parse("2026-12-01T09:00:00Z"))

            publisher.caseExtensions.single().keepUntil shouldBe Instant.parse("2027-04-01T08:00:00Z")
        }

        test("the saktype decides the merkelapp, key and title") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

            saker(publisher).ensure(NL, saktype = Saktype.OPPFOLGINGSPLAN) shouldBe
                SakResolution.Ready("OPPFOLGINGSPLAN:$TEST_LEDERRELASJON_ID", "Oppfølging")

            publisher.caseCreates.single().tittel shouldBe "Oppfølgingsplan"
        }

        test("an Altinn case is keyed on a stable pseudonym that reveals neither organisation nor Sykmeldt") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)
            val altinn = Saksmottaker.AltinnRessurs("nav_syfo_dialogmote", TEST_SYKMELDT)

            val first = saker(publisher).ensure(altinn) as SakResolution.Ready
            val again = saker(FakeArbeidsgiversakPublisher()).ensure(altinn) as SakResolution.Ready
            val other = saker(FakeArbeidsgiversakPublisher()).ensure(altinn.copy(sykmeldt = TEST_SYKMELDT_2)) as SakResolution.Ready

            first.grupperingsid shouldStartWith "DIALOGMOTE:nav_syfo_dialogmote:"
            first.grupperingsid.substringAfterLast(':') shouldHaveLength 32
            first.grupperingsid shouldBe again.grupperingsid
            first.grupperingsid shouldNotBe other.grupperingsid
            first.grupperingsid shouldNotContain TEST_SYKMELDT.value
            first.grupperingsid shouldNotContain TEST_ORGNUMMER.value
            publisher.caseCreates.single().apply {
                lenke shouldBe null
                recipient shouldBe ArbeidsgiverNotificationRecipient.AltinnRessurs("nav_syfo_dialogmote")
            }
        }

        test("a deleted case moves on to the next generation of its key") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, DELETED, NOT_FOUND)
            val names = FakePersonnavnLookup()

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")

            publisher.caseCreates.map { it.grupperingsid } shouldBe
                listOf("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "DIALOGMOTE:$TEST_LEDERRELASJON_ID:2")
            names.lookups shouldBe 1
        }

        test("an existing next generation is found by extending it, without a name lookup or creation") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND)
            val names = FakePersonnavnLookup()

            saker(publisher, names).ensure(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")
            publisher.caseCreates shouldBe emptyList()
            names.lookups shouldBe 0
        }

        test("a rejection is terminal") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))

            saker(publisher).ensure(NL) shouldBe SakResolution.Failed(DeliveryOutcome.Failed("UgyldigMerkelapp"))
        }

        test("a created case that is not visible yet is retried") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, NOT_FOUND)

            shouldThrow<ChannelHandlerFailure> { saker(publisher).ensure(NL) }
        }

        test("sets the requested status on the case with the dispatch as idempotency key") {
            val publisher = FakeArbeidsgiversakPublisher()
            val sak = SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            saker(publisher).setStatus(Channel.KALENDERAVTALE, sak, Saksstatus.FERDIG, CORRELATION_ID) shouldBe null

            publisher.caseStatuses.single() shouldBe
                ArbeidsgiversakStatusRequest("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte", Saksstatus.FERDIG, CORRELATION_ID)
            saker(FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("Konflikt")))
                .setStatus(Channel.KALENDERAVTALE, sak, Saksstatus.FERDIG, CORRELATION_ID) shouldBe DeliveryOutcome.Failed("Konflikt")
        }

        test("titles are capitalised per name part and capped at 140 characters") {
            Saktype.DIALOGMOTE.tittel(Personnavn("ANNE-MARIE", "von", "NORDMANN")) shouldBe "Dialogmøte med Anne-Marie Von Nordmann"
            Saktype.DIALOGMOTE.tittel(null) shouldBe "Dialogmøte"
            Saktype.OPPFOLGINGSPLAN.tittel(Personnavn("SYNTETISK", null, "SYKMELDT")) shouldBe "Oppfølgingsplan for Syntetisk Sykmeldt"
            Saktype.DIALOGMOTE.tittel(Personnavn("A".repeat(200), null, "B")) shouldHaveLength 140
            Personnavn("SYNTETISK", null, "SYKMELDT").toString() shouldNotContain "SYNTETISK"
        }

        test("the configuration never shows the pseudonym key") {
            CONFIG.toString() shouldNotContain CONFIG.pseudonymNokkel
            CONFIG.toString().shouldBeInstanceOf<String>()
        }
    })

private val CONFIG =
    ArbeidsgiversakConfig(
        dineSykmeldteUrl = "https://dinesykmeldte.test/sykmeldte/",
        pseudonymNokkel = "syntetisk-pseudonymnokkel-for-enhetstester",
    )

private fun saker(
    publisher: FakeArbeidsgiversakPublisher,
    names: FakePersonnavnLookup = FakePersonnavnLookup(),
) = Arbeidsgiversaker(
    publisher = publisher,
    personnavn = names,
    config = CONFIG,
    clock =
        object : Clock {
            override fun now(): Instant = NOW
        },
)

private suspend fun Arbeidsgiversaker.ensure(
    mottaker: Saksmottaker,
    saktype: Saktype = Saktype.DIALOGMOTE,
    activityAt: Instant? = null,
) = ensure(
    channel = Channel.ARBEIDSGIVERVARSEL,
    saktype = saktype,
    virksomhetsnummer = TEST_ORGNUMMER.value,
    mottaker = mottaker,
    correlationId = CORRELATION_ID,
    activityAt = activityAt,
)
