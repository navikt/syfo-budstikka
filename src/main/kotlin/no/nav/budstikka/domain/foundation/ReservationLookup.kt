package no.nav.budstikka.domain.foundation

import no.nav.budstikka.domain.dispatch.PersonIdentifier

fun interface ReservationLookup {
    /**
     * @return `true` when [ident] cannot receive a digital notification (and must receive a letter), otherwise `false`.
     */
    suspend fun isReserved(ident: PersonIdentifier): Boolean
}
