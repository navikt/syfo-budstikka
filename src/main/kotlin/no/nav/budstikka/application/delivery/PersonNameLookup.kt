package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.PersonName

/** Looks up a person's name at Decision for a saved sak-title snapshot. */
interface PersonNameLookup {
    /** The registered name, or `null` when the register has none. */
    suspend fun findName(ident: PersonIdentifier): PersonName?
}
