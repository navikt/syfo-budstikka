package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.contract.Ledervarsel
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.LedervarselInactivate
import kotlin.time.Clock

/**
 * Maps [Ledervarsel] to the Dine Sykmeldte wire format using a local DTO. The Kafka key is
 * `reference`, which is also the consumer's identifier; the sykmeldt identifier exists only in the
 * payload and must never be logged.
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
 * boundary. `timestamp` and `utlopstidspunkt` are ISO-8601 strings.
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
