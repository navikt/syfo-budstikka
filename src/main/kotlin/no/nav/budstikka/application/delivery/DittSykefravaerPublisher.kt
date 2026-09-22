package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.DittSykefravaer

/** Sends a Ditt Sykefravær create or inactivate message using the stable delivery reference. */
fun interface DittSykefravaerPublisher {
    suspend fun publish(
        reference: String,
        dittSykefravaer: DittSykefravaer,
    )
}
