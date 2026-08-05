package no.nav.produsenteksempel

import no.nav.budstikka.contract.BrevCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Varseltype

/** The raw CREATE DTOs skip the facade's eager validation of reference, personident and text. */
fun rawBrukervarselCreate(sykmeldt: PersonIdentifier): DispatchContent =
    BrukervarselCreate(personIdentifier = sykmeldt, varseltype = Varseltype.BESKJED, text = "tekst")

fun rawLedervarselCreate(
    sykmeldt: PersonIdentifier,
    orgnummer: Orgnummer,
): DispatchContent =
    LedervarselCreate(
        sykmeldt = sykmeldt,
        orgnummer = orgnummer,
        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
        text = "tekst",
    )

fun rawBrevCreate(sykmeldt: PersonIdentifier): DispatchContent = BrevCreate(personIdentifier = sykmeldt, journalpostId = "jp-1")
