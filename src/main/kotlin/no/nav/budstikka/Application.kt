package no.nav.budstikka

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.api.configureInternalApi
import no.nav.budstikka.api.installPlugins
import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.bootstrap.installDependencyInjection
import no.nav.budstikka.bootstrap.replayDeadLettersIfEnabled
import no.nav.budstikka.bootstrap.startKafkaConsumers
import no.nav.budstikka.bootstrap.startWorkers
import no.nav.budstikka.infrastructure.database.config.migrate
import no.nav.budstikka.infrastructure.metrics.installMetrics
import no.nav.esyfo.observability.Event
import java.lang.invoke.MethodHandles
import javax.sql.DataSource
import kotlin.system.exitProcess

private val logger = applicationLogger(MethodHandles.lookup().lookupClass())

internal object StartupLogEvents {
    val applicationStartupFailed =
        Event<Unit>(
            name = "application.startup.failed",
            level = ApplicationLogLevel.ERROR,
            message = "Budstikka failed to start or stopped due to a fatal error",
            operation = "application.startup",
            errorCode = "APPLICATION_STARTUP_FAILED",
        )
}

const val APPLICATION_NAME = "Budstikka"

fun main(args: Array<String>) {
    logger.debug("$APPLICATION_NAME is starting...")
    try {
        EngineMain.main(args)
    } catch (error: Throwable) {
        logger.event(StartupLogEvents.applicationStartupFailed, error)
        exitProcess(1)
    }
}

/*#
 * Production entrypoint referenced by application.conf (`ApplicationKt.module`). Deliberately zero-arg:
 * Ktor looks up the module by name, so this remains overload-free. It delegates to [configureApplication].
 */
@Suppress("unused")
fun Application.module() {
    configureApplication()
}

fun Application.configureApplication(overrides: DependencyRegistry.() -> Unit = {}) {
    installPlugins()
    installDependencyInjection(overrides)
    installMetrics()
    val dataSource: DataSource by dependencies
    dataSource.migrate()
    replayDeadLettersIfEnabled()
    startKafkaConsumers()
    startWorkers()
    configureInternalApi()
}
