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
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
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
        var nameLookupFailure: Exception? = null
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
                    nameLookupFailure?.let { throw it }
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
            metrics.narmesteLederMissing.clear()
            leaderCalls = 0
            nameCalls = 0
            lookupFailure = null
            nameLookupFailure = null
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
        test("kalender create requires a leader, requires requested email, and resolves optional name for the sak") {
            fun calendar(external: Boolean = false) =
                Dispatch(
                    "calendar",
                    KalenderavtaleCreate(
                        TEST_ORGNUMMER,
                        NarmesteLeder(TEST_SYKMELDT, if (external) NarmesteLederExternalVarsling("title", "body") else null),
                        Sakstype.DIALOGMOTE,
                        "text",
                        "https://nav.no/calendar",
                        Instant.parse("2026-12-01T10:00:00Z"),
                    ),
                )
            relation = null
            process().process(calendar()) shouldBe Decision.Failed("MISSING_ACTIVE_LEADER")
            relation = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
            process().process(calendar(external = true)) shouldBe Decision.Failed("MISSING_EMAIL_ADDRESS")
            nameCalls shouldBe 0
            name = null
            val source = calendar()
            val draft =
                process()
                    .process(source)
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
            draft.snapshot shouldBe DeliverySnapshot.Resolved(relation, null)
            draft.recipient shouldBe Recipient.Virksomhet(TEST_ORGNUMMER)
            draft.content shouldBe source.content
            leaderCalls shouldBe 3
            nameCalls shouldBe 1
        }
        test("terminal gates prevent enrichment; unrelated channels and operations do not look up names or leaders") {
            val wait = Decision.NotInSendingWindow(Instant.parse("2026-11-05T10:00:00Z"), "window")
            val gate = DecisionRule { ResolvedRule { wait } }
            process(listOf(gate)).process(event()) shouldBe wait
            val calendar =
                KalenderavtaleCreate(
                    TEST_ORGNUMMER,
                    NarmesteLeder(TEST_SYKMELDT),
                    Sakstype.DIALOGMOTE,
                    "text",
                    "https://nav.no/calendar",
                    Instant.parse("2026-12-01T10:00:00Z"),
                )
            process(listOf(gate)).process(Dispatch("calendar", calendar)) shouldBe wait
            process(listOf(gate)).process(
                Dispatch(
                    "calendar-update",
                    KalenderavtaleUpdate(TEST_ORGNUMMER, NarmesteLeder(TEST_SYKMELDT), Sakstype.DIALOGMOTE, saksstatus = Saksstatus.FERDIG),
                ),
            ) shouldBe wait
            process().process(Dispatch("other", MicrofrontendEnable(TEST_SYKMELDT, "mf")))
            process().process(Dispatch("inactive", ArbeidsgivervarselInactivate("ref", TEST_ORGNUMMER)))
            leaderCalls shouldBe 0
            nameCalls shouldBe 0
        }
        test("text-only and state-only kalender updates never look up leader or name") {
            val update = KalenderavtaleUpdate(TEST_ORGNUMMER, NarmesteLeder(TEST_SYKMELDT), Sakstype.DIALOGMOTE, text = "updated")
            lookupFailure = IllegalStateException("No lookup is allowed")
            listOf(update, update.copy(text = null, tilstand = Kalenderavtaletilstand.AVHOLDT)).forEach {
                process()
                    .process(Dispatch("calendar-update", it))
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
                    .snapshot shouldBe DeliverySnapshot.Resolved()
            }
            leaderCalls shouldBe 0
            nameCalls shouldBe 0
        }
        listOf(false, true).forEach { external ->
            listOf(false, true).forEach { status ->
                test("kalender update resolves only needed data: external=$external, status=$status") {
                    val recipient = NarmesteLeder(TEST_SYKMELDT, if (external) NarmesteLederExternalVarsling("title", "body") else null)
                    val source =
                        Dispatch(
                            "calendar-update",
                            KalenderavtaleUpdate(
                                TEST_ORGNUMMER,
                                recipient,
                                Sakstype.DIALOGMOTE,
                                tilstand = Kalenderavtaletilstand.AVHOLDT,
                                text = "updated",
                                saksstatus = if (status) Saksstatus.FERDIG else null,
                            ),
                        )
                    if (external || status) {
                        relation = null
                        process().process(source) shouldBe Decision.Failed("MISSING_ACTIVE_LEADER")
                        relation = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
                        if (external) {
                            process().process(source) shouldBe Decision.Failed("MISSING_EMAIL_ADDRESS")
                            relation = relation!!.copy(epostadresser = listOf("snapshot@example.test"))
                        }
                    } else {
                        lookupFailure = IllegalStateException("No lookup is allowed")
                    }
                    val draft =
                        process()
                            .process(source)
                            .shouldBeInstanceOf<Decision.Processed>()
                            .deliveries
                            .single()
                    draft.snapshot shouldBe
                        DeliverySnapshot.Resolved(
                            leader = if (external || status) relation else null,
                            name = if (status) name else null,
                        )
                    draft.content shouldBe source.content
                    draft.recipient shouldBe Recipient.Virksomhet(TEST_ORGNUMMER)
                    leaderCalls shouldBe
                        if (external) {
                            3
                        } else if (status) {
                            2
                        } else {
                            0
                        }
                    nameCalls shouldBe if (status) 1 else 0
                    if (external || status) {
                        metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
                    }
                    if (external) {
                        metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS]?.get() shouldBe 1
                    }
                }
            }
        }
        test("kalender Altinn recipients remain unresolved for the downstream unsupported-recipient failure") {
            // Do not turn the handler's unsupported-recipient error into a Decision lookup failure.
            val recipient = AltinnResource("resource", sykmeldt = TEST_SYKMELDT, sakId = "sak-1")
            val contents =
                listOf(
                    KalenderavtaleCreate(
                        TEST_ORGNUMMER,
                        recipient,
                        Sakstype.DIALOGMOTE,
                        "text",
                        "https://nav.no/calendar",
                        Instant.parse("2026-12-01T10:00:00Z"),
                    ),
                    KalenderavtaleUpdate(TEST_ORGNUMMER, recipient, Sakstype.DIALOGMOTE, text = "updated"),
                    KalenderavtaleUpdate(TEST_ORGNUMMER, recipient, Sakstype.DIALOGMOTE, saksstatus = Saksstatus.FERDIG),
                )
            lookupFailure = IllegalStateException("No lookup is allowed")
            contents.forEach {
                process()
                    .process(Dispatch("altinn-calendar", it))
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
                    .snapshot shouldBe DeliverySnapshot.Resolved()
            }
            leaderCalls shouldBe 0
            nameCalls shouldBe 0
        }
        test("kalender transient leader and name failures are redacted; retry resolves fresh data rather than freezing null") {
            val source =
                Dispatch(
                    "calendar-retry",
                    KalenderavtaleUpdate(
                        TEST_ORGNUMMER,
                        NarmesteLeder(TEST_SYKMELDT),
                        Sakstype.DIALOGMOTE,
                        saksstatus = Saksstatus.FERDIG,
                    ),
                )
            lookupFailure = IllegalStateException("private lookup data")
            val leaderError = shouldThrow<IllegalStateException> { process().process(source) }
            leaderError.message shouldBe "Decision enrichment lookup failed"
            generateSequence<Throwable>(leaderError) { it.cause }
                .map { it.message }
                .toList()
                .all { it == leaderError.message } shouldBe true
            lookupFailure = null
            nameLookupFailure = IllegalStateException("private lookup data")
            val nameError = shouldThrow<IllegalStateException> { process().process(source) }
            nameError.message shouldBe leaderError.message
            generateSequence<Throwable>(nameError) { it.cause }
                .map { it.message }
                .toList()
                .all { it == nameError.message } shouldBe true
            nameLookupFailure = null
            name = null
            process()
                .process(source)
                .shouldBeInstanceOf<Decision.Processed>()
                .deliveries
                .single()
                .snapshot shouldBe DeliverySnapshot.Resolved(relation, null)
            name = PersonName("Synthetic", null, "Retry")
            process()
                .process(source)
                .shouldBeInstanceOf<Decision.Processed>()
                .deliveries
                .single()
                .snapshot shouldBe DeliverySnapshot.Resolved(relation, name)
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
