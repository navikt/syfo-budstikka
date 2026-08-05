package no.nav.produsenteksempel

import no.nav.budstikka.contract.DispatchHeader

/** The header name is mechanics; a Produsent gets it filled in by EncodedDispatch.headers. */
fun rawHeaderName(): String = DispatchHeader.EVENT_ID
