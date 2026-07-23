package no.nav.budstikka.application

import net.logstash.logback.argument.StructuredArgument

internal fun String.withPlaceholders(fields: List<StructuredArgument>) = this + " {}".repeat(fields.size)
