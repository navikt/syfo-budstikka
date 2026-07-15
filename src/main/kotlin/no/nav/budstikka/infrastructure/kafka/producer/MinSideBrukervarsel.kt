package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.dispatch.Brukervarsel
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.ExternalChannel
import no.nav.budstikka.domain.dispatch.ExternalVarsling
import no.nav.tms.varsel.builder.InaktiverVarselBuilder
import no.nav.tms.varsel.builder.OpprettVarselBuilder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import no.nav.budstikka.domain.dispatch.Varseltype as DomainVarseltype
import no.nav.tms.varsel.action.EksternKanal as TmsEksternKanal
import no.nav.tms.varsel.action.Sensitivitet as TmsSensitivitet
import no.nav.tms.varsel.action.Varseltype as TmsVarseltype

private const val DEFAULT_LANGUAGE = "nb"

fun minSideBrukervarselPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
): MinSideBrukervarselPublisher =
    MinSideBrukervarselPublisher { reference, brukervarsel ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = brukervarsel.partitionKey,
                value = brukervarsel.toMessage(reference),
            ),
        )
    }

private fun Brukervarsel.toMessage(reference: String): String =
    when (this) {
        is BrukervarselCreate -> toCreateMessage(reference)
        is BrukervarselInactivate -> toInactivateMessage(reference)
    }

private fun BrukervarselCreate.toCreateMessage(reference: String): String =
    OpprettVarselBuilder
        .newInstance()
        .withVarselId(reference)
        .withIdent(personIdentifier.value)
        .withType(varseltype.toTms())
        .withSensitivitet(TmsSensitivitet.High)
        .withTekst(DEFAULT_LANGUAGE, text, true)
        .apply {
            link?.let(::withLink)
            visibleUntil?.let { withAktivFremTil(it.toZonedDateTime()) }
            externalVarsling?.let { withEksternVarsling(it.toTms()) }
        }.build()

private fun toInactivateMessage(reference: String): String =
    InaktiverVarselBuilder
        .newInstance()
        .withVarselId(reference)
        .build()

private fun DomainVarseltype.toTms(): TmsVarseltype =
    when (this) {
        DomainVarseltype.BESKJED -> TmsVarseltype.Beskjed
        DomainVarseltype.OPPGAVE -> TmsVarseltype.Oppgave
    }

private fun ExternalVarsling.toTms(): OpprettVarselBuilder.EksternVarslingBuilder =
    OpprettVarselBuilder.eksternVarsling().apply {
        preferredChannel()?.let(::withPreferertKanal)
        smsText?.let(::withSmsVarslingstekst)
        emailTitle?.let(::withEpostVarslingstittel)
        emailText?.let(::withEpostVarslingstekst)
    }

private fun ExternalVarsling.preferredChannel(): TmsEksternKanal? =
    when (channels.singleOrNull()) {
        ExternalChannel.SMS -> TmsEksternKanal.SMS
        ExternalChannel.EMAIL -> TmsEksternKanal.EPOST
        null -> null
    }

private fun Instant.toZonedDateTime(): ZonedDateTime = ZonedDateTime.ofInstant(toJavaInstant(), ZoneOffset.UTC)
