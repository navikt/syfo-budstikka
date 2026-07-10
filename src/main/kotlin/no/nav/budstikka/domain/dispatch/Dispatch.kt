package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Konvolutten på den nøytrale Kafka-kontrakten (B22, B43). Bærer korrelasjons-/dedup-id og
 * referanse; selve mottaker + operation ligger i [content] (sealed). Ingen nedstrøms-former
 * lekker inn – kontrakten er anti-corruption-laget (B22).
 */
@Serializable
data class Dispatch(
    @Serializable(with = UuidSerializer::class)
    val eventId: UUID,
    @SerialName("referanse")
    val reference: String,
    val content: DispatchContent,
)

/**
 * Sealed rot for alt content (B22). Operasjonen (CREATE/FERDIGSTILL/aktiver) er kodet inn i
 * typen, så B21 håndheves av kompilatoren. [partitionKey] er Kafka-record-key (B5) =
 * mottakerens id, slik at hendelser for samme mottaker havner ordnet på samme partisjon.
 * Getteren har ingen backing field og serialiseres derfor ikke.
 */
@Serializable
sealed interface DispatchContent {
    val partitionKey: String
}

/**
 * Kanonisk Json-oppsett for [Dispatch]-kontrakten. Polymorf diskriminator er `type`
 * (matcher `@SerialName` på hver variant). `ignoreUnknownKeys` gjør additive felt-tillegg
 * non-breaking for eldre konsumenter/versjoner.
 */
val dispatchJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
