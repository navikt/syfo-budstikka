package no.nav.produsenteksempel

import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.LedervarselInactivate
import no.nav.budstikka.contract.PersonIdentifier

fun rawBrukervarselInactivate(sykmeldt: PersonIdentifier): DispatchContent =
    BrukervarselInactivate(reference = "fixture", sykmeldt = sykmeldt)

fun rawLedervarselInactivate(sykmeldt: PersonIdentifier): DispatchContent =
    LedervarselInactivate(reference = "fixture", sykmeldt = sykmeldt)
