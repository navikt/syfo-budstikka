package no.nav.budstikka.domain.formidling

import kotlinx.serialization.json.Json

/**
 * Kanonisk Json-oppsett for [Formidling]-kontrakten. Polymorf diskriminator er `type`
 * (matcher `@SerialName` på hver variant). `ignoreUnknownKeys` gjør additive felt-tillegg
 * non-breaking for eldre konsumenter/versjoner.
 */
val FormidlingJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
