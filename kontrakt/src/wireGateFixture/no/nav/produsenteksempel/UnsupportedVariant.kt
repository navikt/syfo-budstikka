package no.nav.produsenteksempel

import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Sakstilknytning

/**
 * DittSykefravaer and Arbeidsgivervarsel have no registered channel: budstikka would accept these and
 * never deliver them. They must not read as ordinary producer API.
 */
fun rawDittSykefravaerCreate(sykmeldt: PersonIdentifier): DispatchContent =
    DittSykefravaerCreate(personIdentifier = sykmeldt, text = "tekst")

fun rawDittSykefravaerInactivate(sykmeldt: PersonIdentifier): DispatchContent =
    DittSykefravaerInactivate(reference = "fixture", sykmeldt = sykmeldt)

fun rawNarmesteLeder(sykmeldt: PersonIdentifier): ArbeidsgiverRecipient = NarmesteLeder(sykmeldt = sykmeldt)

fun rawAltinnResource(): ArbeidsgiverRecipient = AltinnResource(resource = "nav_syfo_dialogmote")

fun rawArbeidsgivervarselCreate(
    orgnummer: Orgnummer,
    recipient: ArbeidsgiverRecipient,
): DispatchContent =
    ArbeidsgivervarselCreate(
        orgnummer = orgnummer,
        recipient = recipient,
        tag = "Dialogmøte",
        text = "tekst",
        link = "https://nav.no/ag",
        meldingstype = ArbeidsgiverMeldingstype.BESKJED,
        sakstilknytning = Sakstilknytning(sakId = "sak-1"),
    )

fun rawArbeidsgivervarselInactivate(orgnummer: Orgnummer): DispatchContent =
    ArbeidsgivervarselInactivate(reference = "fixture", orgnummer = orgnummer)
