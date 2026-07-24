package no.nav.budstikka.application

import net.logstash.logback.argument.StructuredArgument

internal fun withPlaceholders(
    template: String,
    fields: List<StructuredArgument>,
) = template + " {}".repeat(fields.size)
