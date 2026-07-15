package no.nav.budstikka.infrastructure.config

import io.ktor.server.config.ApplicationConfig

data class PlatformConfig(
    val clusterName: String,
    val namespace: String,
    val appName: String,
)

fun ApplicationConfig.toPlatformConfig(): PlatformConfig {
    fun value(key: String): String = stringOrEmpty("nais.$key").trim()

    val config =
        PlatformConfig(
            clusterName = value("clusterName"),
            namespace = value("namespace"),
            appName = value("appName"),
        )
    val errors =
        buildList {
            if (config.clusterName.isBlank()) add("nais.clusterName must be set")
            if (config.namespace.isBlank()) add("nais.namespace must be set")
            if (config.appName.isBlank()) add("nais.appName must be set")
        }

    check(errors.isEmpty()) {
        "Invalid platform configuration: ${errors.joinToString(", ")}"
    }

    return config
}
