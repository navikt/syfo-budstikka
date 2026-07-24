package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.domain.dispatch.Ledervarsel
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import kotlin.time.Clock

/**
 * Producer-adapter for LEDERVARSEL (B62, ADR 0009): mapper budstikkas nøytrale [Ledervarsel] til
 * `navikt/dinesykmeldte-backend` sitt `DineSykmeldteHendelse`-skjema på
 * `team-esyfo.dinesykmeldte-hendelser-v2`. Anti-corruption-lag (B23): domenetypene lekker aldri ut
 * — vi serialiserer en LOKAL DTO som speiler konsumentens felt eksakt (verifisert mot
 * konsumentens kildekode: `orgnummer`, `oppgavetype: String`, ISO-8601-timestamps).
 *
 * Kafka-key = `reference` (= konsumentens `id`, dedup-PK `(id, oppgavetype)`), IKKE fnr.
 * `ansattFnr` (Fortrolig, B42) bæres KUN i payloaden, aldri i logg (B46).
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
 * Kanonisk Json for dinesykmeldte-wiren. `explicitNulls = false` utelater fravær-felt
 * (konsumentens valgfrie felt er nullable + `FAIL_ON_UNKNOWN_PROPERTIES=false`) → rene meldinger.
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
 * Lokal speiling av konsumentens wire-skjema (`no.nav.syfo.hendelser.kafka.model` i
 * dinesykmeldte-backend). Bevisst IKKE delt/importert — budstikka eier sin egen anti-corruption-
 * grense (B23). `timestamp`/`utlopstidspunkt` er ISO-8601-strenger (konsumentens Jackson leser dem
 * som `OffsetDateTime`, `WRITE_DATES_AS_TIMESTAMPS=false`).
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
