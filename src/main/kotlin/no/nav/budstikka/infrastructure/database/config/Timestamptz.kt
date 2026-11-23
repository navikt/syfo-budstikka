package no.nav.budstikka.infrastructure.database.config

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * A `TIMESTAMPTZ` column as [Instant]. Exposed's `timestamp` binds a value through a local date-time
 * in the JVM's time zone, so when Oslo leaves summer time the second 02:30 is stored as the first.
 * This type binds the instant with its UTC offset, so it is stored exactly as given.
 */
fun Table.timestamptz(name: String): Column<Instant> = registerColumn(name, TimestamptzColumnType())

private class TimestamptzColumnType : InstantColumnType<Instant>() {
    override fun toInstant(value: Instant): Instant = value

    override fun fromInstant(instant: Instant): Instant = instant

    override fun sqlType(): String = currentDialect.dataTypeProvider.timestampWithTimeZoneType()

    override fun notNullValueToDB(value: Instant): Any = OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)
}
