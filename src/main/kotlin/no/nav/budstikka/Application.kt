package no.nav.budstikka

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.api.configureInternalApi
import no.nav.budstikka.api.installPlugins
import no.nav.budstikka.bootstrap.installDependencyInjection
import no.nav.budstikka.bootstrap.startKafkaConsumers
import no.nav.budstikka.bootstrap.startWorkers
import no.nav.budstikka.infrastructure.database.config.migrate
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import javax.sql.DataSource
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

const val APPLICATION_NAME = "Budstikka"

fun main(args: Array<String>) {
    logger.debug("$APPLICATION_NAME is starting...")
    try {
        EngineMain.main(args)
    } catch (error: Throwable) {
        logger.error("$APPLICATION_NAME failed to start or stopped due to a fatal error", error)
        exitProcess(1)
    }
}

/**
 * Prod-entrypoint referert fra application.conf (`ApplicationKt.module`). Zero-arg med vilje:
 * Ktor slår opp modulen ved navn, så vi holder denne overload-fri. Den delegerer til
 * [configureApplication] uten overrides — prod wirer utelukkende de ekte adapterne.
 */
@Suppress("unused")
fun Application.module() {
    configureApplication()
}

/**
 * Wiring-sømmen (B50/B44): tar en [overrides]-blokk som kjøres SIST i DI-registreringen, slik at
 * et test-/lokalt løp kan bytte ekte adaptere mot fakes uten en `USE_FAKES`-bryter i `src/main`.
 * Overstyring krever `ktor.di.conflictPolicy = "OverridePrevious"` i konfigen (settes kun i
 * test-/lokal-konfig, aldri i prod). Prod kaller [module] og sender ingen overrides.
 */
fun Application.configureApplication(overrides: DependencyRegistry.() -> Unit = {}) {
    installPlugins()
    installDependencyInjection(overrides)
    val dataSource: DataSource by dependencies
    dataSource.migrate()
    startKafkaConsumers()
    startWorkers()
    configureInternalApi()
}
