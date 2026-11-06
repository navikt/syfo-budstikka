package no.nav.budstikka.domain

import no.nav.budstikka.contract.PersonIdentifier
import java.util.UUID

/** @property relationId identifier assigned by esyfo-narmesteleder to this relation record. */
data class NarmesteLederRelasjon(
    val narmesteLederFnr: PersonIdentifier,
    val epostadresser: List<String>,
    val relationId: UUID,
) {
    override fun toString(): String = "NarmesteLederRelasjon(hasEmailAddresses=${epostadresser.isNotEmpty()})"
}
