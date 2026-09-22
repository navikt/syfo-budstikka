package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.delivery.DittSykefravaerPublisher
import no.nav.budstikka.contract.DittSykefravaer
import no.nav.budstikka.contract.DittSykefravaerCreate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import kotlin.time.Clock

/**
 * Maps Ditt Sykefravær to Flex's existing Kafka contract. The Kafka key is the stable Budstikka
 * reference; the person identifier is payload-only and must never be logged.
 */
fun dittSykefravaerPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
    clock: Clock = Clock.System,
): DittSykefravaerPublisher =
    DittSykefravaerPublisher { reference, dittSykefravaer ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = reference,
                value = dittSykefravaer.toMessage(clock),
            ),
        )
    }

private val dittSykefravaerJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

private fun DittSykefravaer.toMessage(clock: Clock): String =
    when (this) {
        is DittSykefravaerCreate ->
            dittSykefravaerJson.encodeToString(
                DittSykefravaerMeldingDto(
                    opprettMelding =
                        OpprettMeldingDto(
                            tekst = text,
                            lenke = link,
                            meldingType = messageType,
                            synligFremTil = visibleUntil?.toString(),
                        ),
                    lukkMelding = null,
                    fnr = personIdentifier.value,
                ),
            )
        is DittSykefravaerInactivate ->
            dittSykefravaerJson.encodeToString(
                DittSykefravaerMeldingDto(
                    opprettMelding = null,
                    lukkMelding = LukkMeldingDto(timestamp = clock.now().toString()),
                    fnr = sykmeldt.value,
                ),
            )
    }

/** Local mirror of Flex's consumer DTO; deliberately not a shared JVM contract. */
@Serializable
private data class DittSykefravaerMeldingDto(
    val opprettMelding: OpprettMeldingDto?,
    val lukkMelding: LukkMeldingDto?,
    val fnr: String,
)

@Serializable
private data class OpprettMeldingDto(
    val tekst: String,
    val lenke: String?,
    val variant: String = "INFO",
    val lukkbar: Boolean = true,
    val meldingType: String,
    val synligFremTil: String?,
)

@Serializable
private data class LukkMeldingDto(
    val timestamp: String,
)
