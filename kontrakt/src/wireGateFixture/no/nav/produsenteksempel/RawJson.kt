package no.nav.produsenteksempel

import kotlinx.serialization.json.Json
import no.nav.budstikka.contract.dispatchJson

/** The canonical JSON configuration: producing bytes without it silently breaks the consumer. */
fun rawJson(): Json = dispatchJson
