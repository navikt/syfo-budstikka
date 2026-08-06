package no.nav.produsenteksempel

import no.nav.budstikka.contract.Brukervarsel
import no.nav.budstikka.contract.Ledervarsel

/** The channel markers are budstikka's own routing seam, not a producer-facing classification. */
fun rawBrukervarselKey(varsel: Brukervarsel): String = varsel.partitionKey

fun rawLedervarselKey(varsel: Ledervarsel): String = varsel.partitionKey
