package no.nav.budstikka.infrastructure.database.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

data class DatabaseConfig(
    val username: String,
    val password: String,
    val jdbcUrl: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeout: Long,
    val idleTimeout: Long,
    val maxLifetime: Long,
) {
    override fun toString(): String = "DatabaseConfig(username=$username, ****** jdbcUrl=$jdbcUrl)"
}

fun ApplicationConfig.toDatabaseConfig() =
    with(configFor("database")) {
        val username = this("username")
        val password = this("password")
        val url = this("url")
        val sslKey = this("sslKey")
        val hikari = configFor("database.hikari")
        DatabaseConfig(
            username = username,
            password = password,
            jdbcUrl = "jdbc:${url.withoutCredentials().withSslKey(sslKey)}",
            maximumPoolSize = hikari("maximumPoolSize").toIntOrNull() ?: 3,
            minimumIdle = hikari("minimumIdle").toIntOrNull() ?: 1,
            connectionTimeout = hikari("connectionTimeout").toLongOrNull() ?: 10_000L,
            idleTimeout = hikari("idleTimeout").toLongOrNull() ?: 300_000L,
            maxLifetime = hikari("maxLifetime").toLongOrNull() ?: 1_800_000L,
        ).validate { config ->
            buildList {
                if (config.username.isBlank()) add("database.username must be set")
                if (config.password.isBlank()) add("database.password must be set")
                if (config.jdbcUrl.isBlank()) add("database.url must be set")
            }
        }
    }

// NAIS provides the database url with credentials embedded (******host...).
// https://doc.nais.io/persistence/cloudsql/reference/
// Hikari takes username/password separately, so we strip the credentials before prefixing jdbc:.
private fun String.withoutCredentials(): String = replaceFirst(Regex("://[^@/]+@"), "://")

// The NAIS url points sslkey at the PEM key, but the JDBC driver needs the PKCS#8 (DER) key
// from FLAGGSKIPET_DB_SSLKEY_PK8. Locally there is no sslkey, so this is a no-op.
private fun String.withSslKey(sslKey: String): String = if (sslKey.isBlank()) this else replace(Regex("sslkey=[^&]*")) { "sslkey=$sslKey" }
