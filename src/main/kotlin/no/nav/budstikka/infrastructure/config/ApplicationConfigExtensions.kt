package no.nav.syfo.no.nav.budstikka.infrastructure.config

import io.ktor.server.config.ApplicationConfig

// Shared reader for all *Config.fromConfig(...) parsers: returns "" for a missing property
// so each config can do its own aggregated validation.
internal fun ApplicationConfig.stringOrEmpty(path: String): String = propertyOrNull(path)?.getString().orEmpty()
