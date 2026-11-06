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
        test("an existing sak is extended and used without creation") {
            val publisher = FakeArbeidsgiversakPublisher()

            saker(publisher).extendOrCreate(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            publisher.requests shouldBe
                listOf(
                    ArbeidsgiversakExtendRequest(
                        grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                        tag = "Dialogmøte",
                        keepUntil = FOUR_MONTHS_LATER,
                        correlationId = CORRELATION_ID,
                    ),
                )
        }

        test("a missing sak is created with the supplied name and a link to Dine Sykmeldte, then extended") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

            saker(publisher).extendOrCreate(NL, name = PersonName("SYNTETISK", null, "SYKMELDT")) shouldBe
                SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

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

            saker(publisher).extendOrCreate(NL, activityAt = Instant.parse("2026-12-01T09:00:00Z"))

            publisher.sakExtensions.single().keepUntil shouldBe Instant.parse("2027-04-01T08:00:00Z")
        }

        test("the sakstype decides the merkelapp, key and title") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

            saker(publisher).extendOrCreate(NL, sakstype = Sakstype.OPPFOLGINGSPLAN) shouldBe
                SakResolution.Ready("OPPFOLGINGSPLAN:$TEST_LEDERRELASJON_ID", "Oppfølging")

            publisher.sakCreates.single().title shouldBe "Oppfølgingsplan"
        }

        test("an Altinn sak is keyed on the resource, the organisation and the producer's sak key, without a link") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

            saker(publisher).extendOrCreate(
                Saksmottaker.AltinnRessurs("nav_syfo_dialogmote", TEST_SYKMELDT, "dialog-1"),
                name = PersonName("SYNTETISK", null, "SYKMELDT"),
            ) shouldBe
                SakResolution.Ready("DIALOGMOTE:nav_syfo_dialogmote:${TEST_ORGNUMMER.value}:dialog-1", "Dialogmøte")

            publisher.sakCreates.single().apply {
                title shouldBe "Dialogmøte med Syntetisk Sykmeldt"
                link shouldBe null
                recipient shouldBe ArbeidsgiverNotificationRecipient.AltinnRessurs("nav_syfo_dialogmote")
            }
        }

        listOf(NL, Saksmottaker.AltinnRessurs("nav_syfo_dialogmote", TEST_SYKMELDT, "dialog-1")).forEach { recipient ->
            test("an explicit null name gives a general title for ${recipient::class.simpleName}") {
                val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)

                saker(publisher).extendOrCreate(recipient, name = null)

                publisher.sakCreates.single().title shouldBe "Dialogmøte"
            }
        }

        test("the shared notification link is the sak destination, with or without a trailing slash in the base") {
            listOf("https://dinesykmeldte.test/sykmeldte", "https://dinesykmeldte.test/sykmeldte/").forEach { base ->
                val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND)
                val service = saker(publisher, config = ArbeidsgiversakConfig(base))

                service.extendOrCreate(NL, name = null)

                service.dineSykmeldteLink(NL.relationId) shouldBe "https://dinesykmeldte.test/sykmeldte/$TEST_LEDERRELASJON_ID"
                publisher.sakCreates.single().link shouldBe service.dineSykmeldteLink(NL.relationId)
            }
        }

        test("a deleted sak moves on to the next generation of its key") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, DELETED, NOT_FOUND, NOT_FOUND)

            saker(publisher).extendOrCreate(NL, name = PersonName("SYNTETISK", null, "SYKMELDT")) shouldBe
                SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")

            publisher.sakCreates.map { it.grupperingsid } shouldBe
                listOf("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "DIALOGMOTE:$TEST_LEDERRELASJON_ID:2")
            publisher.sakCreates.map { it.title } shouldBe
                listOf("Dialogmøte med Syntetisk Sykmeldt", "Dialogmøte med Syntetisk Sykmeldt")
        }

        test("an existing next generation is found by extending it, without creation") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND)

            saker(publisher).extendOrCreate(NL) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID:2", "Dialogmøte")
            publisher.sakCreates shouldBe emptyList()
        }

        test("a rejection is terminal") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))

            saker(publisher).extendOrCreate(NL) shouldBe SakResolution.Failed(DeliveryOutcome.Failed("UgyldigMerkelapp"))
        }

        test("a created sak that is not visible yet is retried with the same supplied name and key") {
            val publisher = FakeArbeidsgiversakPublisher(NOT_FOUND, NOT_FOUND, NOT_FOUND, NOT_FOUND, NOT_FOUND)
            val service = saker(publisher)
            val name = PersonName("SYNTETISK", null, "SYKMELDT")

            shouldThrow<ChannelHandlerFailure> { service.extendOrCreate(NL, name = name) }
            service.extendOrCreate(NL, name = name) shouldBe SakResolution.Ready("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte")

            publisher.sakCreates.size shouldBe 2
            publisher.sakCreates[0] shouldBe publisher.sakCreates[1]
            publisher.sakCreates[1].title shouldBe "Dialogmøte med Syntetisk Sykmeldt"
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

        test("an Altinn sak key is 1-64 letters, digits, '-' or '_'") {
            Saksmottaker.AltinnRessurs.isValidSakId("4711") shouldBe true
            Saksmottaker.AltinnRessurs.isValidSakId("0f8fad5b-d9cb-469f-a165-a1b2c3d4e5f6") shouldBe true
            listOf("", "dialog:2", "dialog 2", "x".repeat(65)).forEach {
                Saksmottaker.AltinnRessurs.isValidSakId(it) shouldBe false
            }
        }
    })

private val CONFIG =
    ArbeidsgiversakConfig(
        dineSykmeldteUrl = "https://dinesykmeldte.test/sykmeldte/",
    )

private fun saker(
    publisher: FakeArbeidsgiversakPublisher,
    config: ArbeidsgiversakConfig = CONFIG,
) = Arbeidsgiversaker(
    publisher = publisher,
    config = config,
    clock =
        object : Clock {
            override fun now(): Instant = NOW
        },
)

private suspend fun Arbeidsgiversaker.extendOrCreate(
    mottaker: Saksmottaker,
    sakstype: Sakstype = Sakstype.DIALOGMOTE,
    activityAt: Instant? = null,
    name: PersonName? = null,
) = extendOrCreate(
    channel = Channel.ARBEIDSGIVERVARSEL,
    sakstype = sakstype,
    virksomhetsnummer = TEST_ORGNUMMER.value,
    mottaker = mottaker,
    name = name,
    correlationId = CORRELATION_ID,
    activityAt = activityAt,
)
