package no.nav.budstikka.domain.dispatch.dsl

import no.nav.budstikka.domain.dispatch.AltinnResource
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgiverMeldingstype
import no.nav.budstikka.domain.dispatch.ArbeidsgiverRecipient
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrevFallback
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.DistributionType
import no.nav.budstikka.domain.dispatch.DittSykefravaerCreate
import no.nav.budstikka.domain.dispatch.DittSykefravaerInactivate
import no.nav.budstikka.domain.dispatch.EksternKanal
import no.nav.budstikka.domain.dispatch.EksternVarsling
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import no.nav.budstikka.domain.dispatch.Merkelapp
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.NarmesteLeder
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Sakstilknytning
import no.nav.budstikka.domain.dispatch.SendingWindow
import no.nav.budstikka.domain.dispatch.Varseltype
import kotlin.time.Instant

/**
 * Kotlin DSL for å bygge og serialisere en [no.nav.budstikka.domain.dispatch.Dispatch] (ADR 0011,
 * B64). Prinsipp: **påkrevde** felt er funksjonsparametre, **valgfrie** felt settes i en `@DispatchDsl`-lambda.
 * Hver funksjon returnerer [EncodedDispatch] (serialisert) med en generert eventId, klar for produsentens egen `ProducerRecord`.
 */
@DslMarker
annotation class DispatchDsl

// --- Nøstet: ekstern varsling (SMS/e-post) ------------------------------------------------------

@DispatchDsl
class EksternVarslingBuilder {
    private val valgteKanaler = mutableSetOf<EksternKanal>()
    var smsTekst: String? = null
    var epostTittel: String? = null
    var epostTekst: String? = null

    /** Varsling på `SMS` og/eller `e-post`. Bibliotekets default er ingen ekstern varsling (tom liste). */
    fun kanaler(vararg kanaler: EksternKanal) {
        valgteKanaler += kanaler
    }

    internal fun build(): EksternVarsling =
        if (valgteKanaler.isEmpty()) {
            EksternVarsling(smsTekst = smsTekst, epostTittel = epostTittel, epostTekst = epostTekst)
        } else {
            EksternVarsling(
                kanaler = valgteKanaler.toSet(),
                smsTekst = smsTekst,
                epostTittel = epostTittel,
                epostTekst = epostTekst,
            )
        }
}

// --- Arbeidsgiver-mottaker ----------------------------------------------------------------------

/** Personlig mottaker; budstikka resolver nærmeste leder selv (B24) fra `(sykmeldt, orgnummer)`. */
fun narmesteLeder(sykmeldt: PersonIdentifier): ArbeidsgiverRecipient = NarmesteLeder(sykmeldt)

/** Alle med Altinn-rollen ved virksomheten (B32). */
fun altinnRessurs(resource: AltinnResourceId): ArbeidsgiverRecipient = AltinnResource(resource)

// --- Brukervarsel (CREATE) ----------------------------------------------------------------------

@DispatchDsl
class BrukervarselCreateBuilder {
    var link: String? = null
    var visibleUntil: Instant? = null
    var sendingWindow: SendingWindow? = null
    private var eksternVarsling: EksternVarsling? = null
    private var brevFallback: BrevFallback? = null

    fun eksternVarsling(block: EksternVarslingBuilder.() -> Unit) {
        eksternVarsling = EksternVarslingBuilder().apply(block).build()
    }

    /** B8: send brev når mottakeren er reservert mot digital kontakt. */
    fun brevFallback(
        journalpostId: String,
        distributionType: DistributionType = DistributionType.IMPORTANT,
    ) {
        brevFallback = BrevFallback(journalpostId, distributionType)
    }

    internal fun build(
        personIdentifier: PersonIdentifier,
        varseltype: Varseltype,
        text: String,
    ) = BrukervarselCreate(
        personIdentifier = personIdentifier,
        varseltype = varseltype,
        text = text,
        link = link,
        visibleUntil = visibleUntil,
        eksternVarsling = eksternVarsling,
        brevFallback = brevFallback,
        sendingWindow = sendingWindow,
    )
}

fun brukervarselCreate(
    reference: String,
    personIdentifier: PersonIdentifier,
    varseltype: Varseltype,
    text: String,
    block: BrukervarselCreateBuilder.() -> Unit = {},
): EncodedDispatch =
    BrukervarselCreateBuilder().apply(block).build(personIdentifier, varseltype, text).encode(reference)

// --- Ledervarsel (CREATE) -----------------------------------------------------------------------

@DispatchDsl
class LedervarselCreateBuilder {
    var link: String? = null
    var visibleUntil: Instant? = null
    var sendingWindow: SendingWindow? = null
    private var eksternVarsling: EksternVarsling? = null

    fun eksternVarsling(block: EksternVarslingBuilder.() -> Unit) {
        eksternVarsling = EksternVarslingBuilder().apply(block).build()
    }

    internal fun build(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
        text: String,
    ) = LedervarselCreate(
        sykmeldt = sykmeldt,
        orgnummer = orgnummer,
        text = text,
        link = link,
        visibleUntil = visibleUntil,
        eksternVarsling = eksternVarsling,
        sendingWindow = sendingWindow,
    )
}

fun ledervarselCreate(
    reference: String,
    sykmeldt: PersonIdentifier,
    orgnummer: Orgnummer,
    text: String,
    block: LedervarselCreateBuilder.() -> Unit = {},
): EncodedDispatch = LedervarselCreateBuilder().apply(block).build(sykmeldt, orgnummer, text).encode(reference)

// --- Ditt sykefravær (CREATE) -------------------------------------------------------------------

fun dittSykefravaerCreate(
    reference: String,
    personIdentifier: PersonIdentifier,
    text: String,
    link: String? = null,
    visibleUntil: Instant? = null,
): EncodedDispatch =
    DittSykefravaerCreate(
        personIdentifier = personIdentifier,
        text = text,
        link = link,
        visibleUntil = visibleUntil,
    ).encode(reference)

// --- Arbeidsgivervarsel (CREATE) ----------------------------------------------------------------

@DispatchDsl
class ArbeidsgivervarselCreateBuilder {
    var meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED
    var visibleUntil: Instant? = null
    var sendingWindow: SendingWindow? = null
    private var eksternVarsling: EksternVarsling? = null
    private var sakstilknytning: Sakstilknytning? = null

    fun eksternVarsling(block: EksternVarslingBuilder.() -> Unit) {
        eksternVarsling = EksternVarslingBuilder().apply(block).build()
    }

    /** Saksid benyttes til å gruppere varslene downstream. */
    fun sakstilknytning(sakId: String) {
        sakstilknytning = Sakstilknytning(sakId)
    }

    internal fun build(
        orgnummer: Orgnummer,
        recipient: ArbeidsgiverRecipient,
        merkelapp: Merkelapp,
        text: String,
        link: String,
    ) = ArbeidsgivervarselCreate(
        orgnummer = orgnummer,
        recipient = recipient,
        merkelapp = merkelapp,
        text = text,
        link = link,
        eksternVarsling = eksternVarsling,
        meldingstype = meldingstype,
        sakstilknytning = sakstilknytning,
        visibleUntil = visibleUntil,
        sendingWindow = sendingWindow,
    )
}

/**
 * Varsel til min side arbeidsgiver med mulighet for ekstern varsling via SMS eller e-post.
 * @see [ArbeidsgivervarselCreate]
 * */
fun arbeidsgivervarselCreate(
    reference: String,
    orgnummer: Orgnummer,
    recipient: ArbeidsgiverRecipient,
    merkelapp: Merkelapp,
    text: String,
    link: String,
    block: ArbeidsgivervarselCreateBuilder.() -> Unit = {},
): EncodedDispatch =
    ArbeidsgivervarselCreateBuilder().apply(block).build(orgnummer, recipient, merkelapp, text, link).encode(reference)

// --- Brev (CREATE) ------------------------------------------------------------------------------

/**
 * Brev-utsending til mottaker med digital kontaktreservasjon.
 * Default `distributionType` = IMPORTANT.
 */
fun brevCreate(
    reference: String,
    personIdentifier: PersonIdentifier,
    journalpostId: String,
    distributionType: DistributionType = DistributionType.IMPORTANT,
): EncodedDispatch =
    BrevCreate(
        personIdentifier = personIdentifier,
        journalpostId = journalpostId,
        distributionType = distributionType,
    ).encode(reference)

// --- Inactivate (tynne, B38) --------------------------------------------------------------------

fun brukervarselInactivate(
    reference: String,
    sykmeldt: PersonIdentifier,
): EncodedDispatch = BrukervarselInactivate(reference = reference, sykmeldt = sykmeldt).encode(reference)

fun ledervarselInactivate(
    reference: String,
    sykmeldt: PersonIdentifier,
): EncodedDispatch = LedervarselInactivate(reference = reference, sykmeldt = sykmeldt).encode(reference)

fun dittSykefravaerInactivate(
    reference: String,
    sykmeldt: PersonIdentifier,
): EncodedDispatch = DittSykefravaerInactivate(reference = reference, sykmeldt = sykmeldt).encode(reference)

fun arbeidsgivervarselInactivate(
    reference: String,
    orgnummer: Orgnummer,
): EncodedDispatch = ArbeidsgivervarselInactivate(reference = reference, orgnummer = orgnummer).encode(reference)

// --- Microfrontend (B41) ------------------------------------------------------------------------

fun microfrontendEnable(
    reference: String,
    personIdentifier: PersonIdentifier,
    microfrontendId: String,
    visibleUntil: Instant? = null,
): EncodedDispatch =
    MicrofrontendEnable(
        personIdentifier = personIdentifier,
        microfrontendId = microfrontendId,
        visibleUntil = visibleUntil,
    ).encode(reference)

fun microfrontendDisable(
    reference: String,
    personIdentifier: PersonIdentifier,
    microfrontendId: String,
): EncodedDispatch =
    MicrofrontendDisable(
        personIdentifier = personIdentifier,
        microfrontendId = microfrontendId,
    ).encode(reference)
