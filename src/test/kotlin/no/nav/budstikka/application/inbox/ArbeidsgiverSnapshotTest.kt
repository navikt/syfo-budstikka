package no.nav.budstikka.application.inbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.decision.ResolvedRule
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import kotlin.time.Instant

class ArbeidsgiverSnapshotTest :
    FunSpec({
        var leaderCalls = 0
        var nameCalls = 0
        var relation: NarmesteLederRelasjon? = null
        var lookupFailure: Exception? = null
        var name: PersonName? = null
        val metrics = RecordingDeliveryMetrics()
        val lookup =
            NarmesteLederLookup { _, _ ->
                leaderCalls++
                lookupFailure?.let { throw it }
                relation
            }
        val names =
            object : PersonNameLookup {
                override suspend fun findName(ident: PersonIdentifier): PersonName? {
                    nameCalls++
                    lookupFailure?.let { throw it }
                    return name
                }
            }
        val enrichment = ArbeidsgiverDecisionEnrichment(lookup, names, metrics)

        fun process(rules: List<DecisionRule> = emptyList()) = DecisionProcess(rules, enrichment::enrich)

        fun event(
            external: Boolean = false,
            sakstype: Sakstype? = Sakstype.DIALOGMOTE,
        ) = Dispatch(
            "snapshot",
            ArbeidsgivervarselCreate(
                TEST_ORGNUMMER,
                NarmesteLeder(
                    TEST_SYKMELDT,
                    if (external) {
                        NarmesteLederExternalVarsling("title", "body")
                    } else {
                        null
                    },
                ),
                tag = if (sakstype == null) "tag" else null,
                text = "text",
                sakstype = sakstype,
            ),
        )
        beforeTest {
            leaderCalls = 0
            nameCalls = 0
            lookupFailure = null
            relation = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), listOf("snapshot@example.test"), TEST_LEDERRELASJON_ID)
            name = PersonName("Synthetic", null, "Snapshot")
        }
        test("Decision freezes leader and optional name, without changing the routing anchor or wire payload") {
            val source = event()
            val draft =
                process()
                    .process(source)
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
            draft.recipient shouldBe Recipient.Virksomhet(TEST_ORGNUMMER)
            draft.content shouldBe source.content
            draft.snapshot shouldBe DeliverySnapshot.Resolved(relation, name)
            leaderCalls shouldBe 1
            nameCalls shouldBe 1
            relation = null
            name = null
            draft.snapshot
                .shouldBeInstanceOf<DeliverySnapshot.Resolved>()
                .leader
                ?.epostadresser shouldBe listOf("snapshot@example.test")
        }
        test("terminal gates prevent enrichment; unrelated channels and operations do not look up names or leaders") {
            val wait = Decision.NotInSendingWindow(Instant.parse("2026-11-05T10:00:00Z"), "window")
            val gate = DecisionRule { ResolvedRule { wait } }
            process(listOf(gate)).process(event()) shouldBe wait
            process().process(Dispatch("other", MicrofrontendEnable(TEST_SYKMELDT, "mf")))
            process().process(Dispatch("inactive", ArbeidsgivervarselInactivate("ref", TEST_ORGNUMMER)))
            leaderCalls shouldBe 0
            nameCalls shouldBe 0
        }
        test("missing leader and required email fail terminally with bounded metric reasons") {
            relation = null
            process().process(event()) shouldBe Decision.Failed("MISSING_ACTIVE_LEADER")
            relation = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
            process().process(event(external = true)) shouldBe Decision.Failed("MISSING_EMAIL_ADDRESS")
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS]?.get() shouldBe 1
            nameCalls shouldBe 0
        }
        test("no external notification permits empty email; null name is resolved and not legacy") {
            relation = relation!!.copy(epostadresser = emptyList())
            name = null
            val draft =
                process()
                    .process(event())
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
            draft.snapshot shouldBe DeliverySnapshot.Resolved(relation, null)
        }
        test("names are only looked up for sakstype, including Altinn; transient errors remain retryable and redacted") {
            process().process(event(sakstype = null))
            nameCalls shouldBe 0
            val altinn =
                Dispatch(
                    "altinn",
                    ArbeidsgivervarselCreate(
                        TEST_ORGNUMMER,
                        AltinnResource("resource", sykmeldt = TEST_SYKMELDT, sakId = "sak-1"),
                        text = "text",
                        link = "https://altinn.no",
                        sakstype = Sakstype.DIALOGMOTE,
                    ),
                )
            process()
                .process(altinn)
                .shouldBeInstanceOf<Decision.Processed>()
                .deliveries
                .single()
                .snapshot shouldBe
                DeliverySnapshot.Resolved(name = name)
            leaderCalls shouldBe 1
            nameCalls shouldBe 1
            lookupFailure = IllegalStateException("private lookup data")
            val error = shouldThrow<IllegalStateException> { process().process(altinn) }
            error.message shouldBe "Decision enrichment lookup failed"
            generateSequence<Throwable>(error) { it.cause }.map { it.message }.toList().all { it == error.message } shouldBe true
            val leaderError = shouldThrow<IllegalStateException> { process().process(event()) }
            generateSequence<Throwable>(leaderError) { it.cause }.map { it.message }.toList().all { it == error.message } shouldBe true
        }
    })
