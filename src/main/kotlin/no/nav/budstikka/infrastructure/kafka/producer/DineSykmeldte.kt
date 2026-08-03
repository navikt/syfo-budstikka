package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.domain.dispatch.Ledervarsel
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import kotlin.time.Clock

/**
 * Producer adapter for LEDERVARSEL (ADR 0016): maps budstikka's neutral [Ledervarsel] to
 * `navikt/dinesykmeldte-backend`'s `DineSykmeldteHendelse` schema on
 * `team-esyfo.dinesykmeldte-hendelser-v2`. Anti-corruption layer (B23): the domain types never leak
 * out — we serialize a LOCAL DTO that mirrors the consumer's fields exactly (verified against the
 * consumer's source: `orgnummer`, `oppgavetype: String`, ISO-8601 timestamps).
 *
 * Kafka key = `reference` (= the consumer's `id`, dedup PK `(id, oppgavetype)`), NOT an fnr.
 * `ansattFnr` (Fortrolig, B42) is carried ONLY in the payload, never in a log (B46).
 */
fun ledervarselPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
    clock: Clock = Clock.System,
): LedervarselPublisher =
    LedervarselPublisher { reference, ledervarsel ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = reference,
                value = ledervarsel.toMessage(reference, clock),
            ),
        )
    }

/**
 * Canonical Json for the dinesykmeldte wire. `explicitNulls = false` omits absent fields
 * (the consumer's optional fields are nullable + `FAIL_ON_UNKNOWN_PROPERTIES=false`) → clean messages.
 */
private val dineSykmeldteJson: Json =
    Json {
        encodeDefaults = false
        explicitNulls = false
    }

private fun Ledervarsel.toMessage(
    reference: String,
    clock: Clock,
): String =
    when (this) {
        is LedervarselCreate -> toCreateMessage(reference, clock)
        is LedervarselInactivate -> toInactivateMessage(reference, clock)
    }

private fun LedervarselCreate.toCreateMessage(
    reference: String,
    clock: Clock,
): String =
    dineSykmeldteJson.encodeToString(
        DineSykmeldteHendelseDto(
            id = reference,
            opprettHendelse =
                OpprettHendelseDto(
                    ansattFnr = sykmeldt.value,
                    orgnummer = orgnummer.value,
                    oppgavetype = oppgavetype.wireValue,
                    lenke = link,
                    tekst = text,
                    timestamp = clock.now().toString(),
                    utlopstidspunkt = visibleUntil?.toString(),
                ),
        ),
    )

private fun toInactivateMessage(
    reference: String,
    clock: Clock,
): String =
    dineSykmeldteJson.encodeToString(
        DineSykmeldteHendelseDto(
            id = reference,
            ferdigstillHendelse = FerdigstillHendelseDto(timestamp = clock.now().toString()),
        ),
    )

/**
 * Local mirror of the consumer's wire schema (`no.nav.syfo.hendelser.kafka.model` in
 * dinesykmeldte-backend). Deliberately NOT shared/imported — budstikka owns its own anti-corruption
 * boundary (B23). `timestamp`/`utlopstidspunkt` are ISO-8601 strings (the consumer's Jackson reads
 * them as `OffsetDateTime`, `WRITE_DATES_AS_TIMESTAMPS=false`).
 */
@Serializable
private data class DineSykmeldteHendelseDto(
    val id: String,
    val opprettHendelse: OpprettHendelseDto? = null,
    val ferdigstillHendelse: FerdigstillHendelseDto? = null,
)

@Serializable
private data class OpprettHendelseDto(
    val ansattFnr: String,
    val orgnummer: String,
    val oppgavetype: String,
    val lenke: String? = null,
    val tekst: String? = null,
    val timestamp: String,
    val utlopstidspunkt: String? = null,
)

@Serializable
private data class FerdigstillHendelseDto(
    val timestamp: String,
)
