package no.nav.syfo

import installPlugins
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.di.dependencies
import no.nav.syfo.no.nav.budstikka.api.configureInternalApi
import no.nav.syfo.no.nav.budstikka.bootstrap.installDependencyInjection
import no.nav.syfo.no.nav.budstikka.infrastructure.database.config.migrate
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import javax.sql.DataSource
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

fun main(args: Array<String>) {
    logger.debug("Budstikka is starting...")
    try {
        EngineMain.main(args)
    } catch (error: Throwable) {
        logger.error("Budstikka failed to start or stopped due to a fatal error", error)
        exitProcess(1)
    }
}

@Suppress("unused")
fun Application.module() {
    installPlugins()
    installDependencyInjection()
    val dataSource: DataSource by dependencies
    dataSource.migrate()
    configureInternalApi()
}
