package no.nav.budstikka.infrastructure.database.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty

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

fun ApplicationConfig.toDatabaseConfig(): DatabaseConfig {
    fun value(key: String): String = stringOrEmpty("database.$key")
    fun hikariValue(key: String): String = stringOrEmpty("database.hikari.$key")

    val host = value("host")
    val port = value("port")
    val name = value("name")
    val username = value("username")
    val password = value("password")
    val url = value("url")
    val sslKey = value("sslkey")
    val maximumPoolSize = hikariValue("maximumPoolSize").toIntOrNull() ?: 3
    val minimumIdle = hikariValue("minimumIdle").toIntOrNull() ?: 1
    val connectionTimeout = hikariValue("connectionTimeout").toLongOrNull() ?: 10_000L
    val idleTimeout = hikariValue("idleTimeout").toLongOrNull() ?: 300_000L
    val maxLifetime = hikariValue("maxLifetime").toLongOrNull() ?: 1_800_000L

    val errors =
        buildList {
            if (host.isBlank()) add("database.host must be set")
            when {
                port.isBlank() -> add("database.port must be set")
                (port.toIntOrNull() ?: 0) <= 0 -> add("database.port must be a positive integer")
            }
            if (name.isBlank()) add("database.name must be set")
            if (username.isBlank()) add("database.username must be set")
            if (password.isBlank()) add("database.password must be set")
            if (url.isBlank()) add("database.url must be set")
        }

    check(errors.isEmpty()) {
        "Invalid database configuration: ${errors.joinToString(", ")}"
    }

    return DatabaseConfig(
        username = username,
        password = password,
        jdbcUrl = "jdbc:${url.withoutCredentials().withSslKey(sslKey)}",
        maximumPoolSize = maximumPoolSize,
        minimumIdle = minimumIdle,
        connectionTimeout = connectionTimeout,
        idleTimeout = idleTimeout,
        maxLifetime = maxLifetime,
    )
}

// NAIS provides the database url with credentials embedded (******host...).
// https://doc.nais.io/persistence/cloudsql/reference/
// Hikari takes username/password separately, so we strip the credentials before prefixing jdbc:.
private fun String.withoutCredentials(): String = replaceFirst(Regex("://[^@/]+@"), "://")

// The NAIS url points sslkey at the PEM key, but the JDBC driver needs the PKCS#8 (DER) key
// from FLAGGSKIPET_DB_SSLKEY_PK8. Locally there is no sslkey, so this is a no-op.
private fun String.withSslKey(sslKey: String): String = if (sslKey.isBlank()) this else replace(Regex("sslkey=[^&]*")) { "sslkey=$sslKey" }
