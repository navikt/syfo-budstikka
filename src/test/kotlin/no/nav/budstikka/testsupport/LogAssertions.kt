package no.nav.budstikka.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent

fun ILoggingEvent.structuredFields(): Map<String, Any?> =
    keyValuePairs
        .orEmpty()
        .associate { it.key to it.value }

fun ILoggingEvent.renderedLogData(): String =
    buildString {
        append(formattedMessage)
        structuredFields().forEach { (key, value) ->
            append(' ')
            append(key)
            append('=')
            append(value)
        }
        throwableProxy?.message?.let {
            append(' ')
            append(it)
        }
    }
