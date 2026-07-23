package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.dispatch.Brukervarsel
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.EksternKanal
import no.nav.budstikka.domain.dispatch.EksternVarsling
import no.nav.budstikka.infrastructure.config.PlatformConfig
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
    platformConfig: PlatformConfig,
): MinSideBrukervarselPublisher =
    MinSideBrukervarselPublisher { reference, brukervarsel ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = brukervarsel.partitionKey,
                value = brukervarsel.toMessage(reference, platformConfig),
            ),
        )
    }

private fun Brukervarsel.toMessage(
    reference: String,
    platformConfig: PlatformConfig,
): String =
    when (this) {
        is BrukervarselCreate -> toCreateMessage(reference, platformConfig)
        is BrukervarselInactivate -> toInactivateMessage(reference, platformConfig)
    }

private fun BrukervarselCreate.toCreateMessage(
    reference: String,
    platformConfig: PlatformConfig,
): String =
    OpprettVarselBuilder
        .newInstance()
        .withVarselId(reference)
        .withIdent(personIdentifier.value)
        .withType(varseltype.toTms())
        .withSensitivitet(TmsSensitivitet.High)
        .withTekst(DEFAULT_LANGUAGE, text, true)
        .apply {
            withProdusent(platformConfig.clusterName, platformConfig.namespace, platformConfig.appName)
            link?.let(::withLink)
            visibleUntil?.let { withAktivFremTil(it.toZonedDateTime()) }
            eksternVarsling?.let { withEksternVarsling(it.toTms()) }
        }.build()

private fun toInactivateMessage(
    reference: String,
    platformConfig: PlatformConfig,
): String =
    InaktiverVarselBuilder
        .newInstance()
        .withVarselId(reference)
        .withProdusent(platformConfig.clusterName, platformConfig.namespace, platformConfig.appName)
        .build()

private fun DomainVarseltype.toTms(): TmsVarseltype =
    when (this) {
        DomainVarseltype.BESKJED -> TmsVarseltype.Beskjed
        DomainVarseltype.OPPGAVE -> TmsVarseltype.Oppgave
    }

private fun EksternVarsling.toTms(): OpprettVarselBuilder.EksternVarslingBuilder =
    OpprettVarselBuilder.eksternVarsling().apply {
        preferredChannel()?.let(::withPreferertKanal)
        smsTekst?.let(::withSmsVarslingstekst)
        epostTittel?.let(::withEpostVarslingstittel)
        epostTekst?.let(::withEpostVarslingstekst)
    }

private fun EksternVarsling.preferredChannel(): TmsEksternKanal? =
    when (kanaler.singleOrNull()) {
        EksternKanal.SMS -> TmsEksternKanal.SMS
        EksternKanal.EMAIL -> TmsEksternKanal.EPOST
        null -> null
    }

private fun Instant.toZonedDateTime(): ZonedDateTime = ZonedDateTime.ofInstant(toJavaInstant(), ZoneOffset.UTC)
