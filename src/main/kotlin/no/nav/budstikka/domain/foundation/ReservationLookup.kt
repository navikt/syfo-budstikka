package no.nav.budstikka.domain.foundation

import no.nav.budstikka.domain.dispatch.PersonIdentifier

fun interface ReservationLookup {
    /**
     * @return `true` hvis [ident] ikke kan varsles digitalt (skal få brev i stedet), ellers `false`.
     */
    suspend fun isReserved(ident: PersonIdentifier): Boolean
}
