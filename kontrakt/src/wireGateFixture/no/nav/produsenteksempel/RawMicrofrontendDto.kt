package no.nav.produsenteksempel

import no.nav.budstikka.contract.Microfrontend
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.PersonIdentifier

fun rawMicrofrontendEnable(sykmeldt: PersonIdentifier): Microfrontend =
    MicrofrontendEnable(personIdentifier = sykmeldt, microfrontendId = "syk-dialog")

fun rawMicrofrontendDisable(sykmeldt: PersonIdentifier): Microfrontend =
    MicrofrontendDisable(personIdentifier = sykmeldt, microfrontendId = "syk-dialog")
