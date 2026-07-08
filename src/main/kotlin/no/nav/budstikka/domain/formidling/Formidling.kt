package no.nav.budstikka.domain.formidling

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/**
 * Konvolutten på den nøytrale Kafka-kontrakten (B22, B43). Bærer korrelasjons-/dedup-id og
 * referanse; selve mottaker + operasjon ligger i [innhold] (sealed). Ingen nedstrøms-former
 * lekker inn – kontrakten er anti-corruption-laget (B22).
 */
@Serializable
data class Formidling(
    @Serializable(with = UuidSerializer::class)
    val eventId: UUID,
    val referanse: String,
    val innhold: Formidlingsinnhold,
)

/**
 * Sealed rot for alt innhold (B22). Operasjonen (OPPRETT/FERDIGSTILL/aktiver) er kodet inn i
 * typen, så B21 håndheves av kompilatoren. [partisjonsnokkel] er Kafka-record-key (B5) =
 * mottakerens id, slik at hendelser for samme mottaker havner ordnet på samme partisjon.
 * Getteren har ingen backing field og serialiseres derfor ikke.
 */
@Serializable
sealed interface Formidlingsinnhold {
    val partisjonsnokkel: String
}

/** Serialiserer [UUID] som ISO-streng. Repoets standard er java.util.UUID (unngår experimental opt-in). */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
