package no.nav.budstikka.infrastructure.config

import io.ktor.server.config.ApplicationConfig

internal fun ApplicationConfig.stringOrEmpty(path: String): String = propertyOrNull(path)?.getString().orEmpty()

internal fun ApplicationConfig.configFor(prefix: String): (String) -> String =
    { key ->
        stringOrEmpty("$prefix.$key").trim()
    }

internal fun <T> T.validate(block: (T) -> List<String>): T =
    block(this).let { errors ->
        check(errors.isEmpty()) {
            "Invalid configuration: ${errors.joinToString(", ")}"
        }
        this
    }
