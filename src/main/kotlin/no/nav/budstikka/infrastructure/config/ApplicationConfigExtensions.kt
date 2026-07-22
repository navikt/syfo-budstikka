package no.nav.budstikka.infrastructure.config

import io.ktor.server.config.ApplicationConfig

internal fun ApplicationConfig.stringOrEmpty(path: String): String = propertyOrNull(path)?.getString().orEmpty()
