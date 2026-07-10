package no.nav.budstikka

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.di.dependencies
import no.nav.budstikka.api.configureInternalApi
import no.nav.budstikka.api.installPlugins
import no.nav.budstikka.bootstrap.installDependencyInjection
import no.nav.budstikka.bootstrap.startKafkaConsumers
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

@Suppress("unused")
fun Application.module() {
    installPlugins()
    installDependencyInjection()
    val dataSource: DataSource by dependencies
    dataSource.migrate()
    startKafkaConsumers()
    configureInternalApi()
}
