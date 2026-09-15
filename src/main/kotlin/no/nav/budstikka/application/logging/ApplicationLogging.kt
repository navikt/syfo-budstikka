package no.nav.budstikka.application.logging

import io.ktor.server.application.Application
import io.ktor.server.application.log
import no.nav.esyfo.observability.ApplicationLogger
import no.nav.esyfo.observability.createLogger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

internal typealias ApplicationLogLevel = Level
internal typealias ApplicationMdc = MDC

internal fun applicationLogger(owner: Class<*>): ApplicationLogger = createLogger(LoggerFactory.getLogger(owner))

internal fun applicationLogger(name: String): ApplicationLogger = createLogger(LoggerFactory.getLogger(name))

internal fun Application.applicationLogger(): ApplicationLogger = createLogger(log)
