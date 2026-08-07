package no.nav.budstikka.domain.foundation

import no.nav.budstikka.contract.PersonIdentifier

fun interface ReservationLookup {
    /**
     * @return `true` when [ident] cannot receive `ekstern varsling` (`kanVarsles=false`), otherwise
     * `false`. `BrevFallback` determines separately whether Reservasjon adds a Brev.
     */
    suspend fun isReserved(ident: PersonIdentifier): Boolean
}
