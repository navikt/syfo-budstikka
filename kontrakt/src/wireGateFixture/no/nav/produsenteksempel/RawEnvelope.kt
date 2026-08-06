package no.nav.produsenteksempel

import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent

/** Assembling the envelope by hand: the single most damaging thing a Produsent could copy. */
fun rawEnvelope(content: DispatchContent): Dispatch = Dispatch(reference = "fixture", content = content)

fun rawPartitionKey(content: DispatchContent): String = content.partitionKey
