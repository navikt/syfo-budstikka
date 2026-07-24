package no.nav.budstikka.infrastructure.config

import io.ktor.server.config.ApplicationConfig

data class PlatformConfig(
    val clusterName: String,
    val namespace: String,
    val appName: String,
)

fun ApplicationConfig.toPlatformConfig() =
    with(configFor("nais")) {
        PlatformConfig(
            clusterName = this("clusterName"),
            namespace = this("namespace"),
            appName = this("appName"),
        ).validate { config ->
            buildList {
                if (config.clusterName.isBlank()) add("nais.clusterName must be set")
                if (config.namespace.isBlank()) add("nais.namespace must be set")
                if (config.appName.isBlank()) add("nais.appName must be set")
            }
        }
    }
