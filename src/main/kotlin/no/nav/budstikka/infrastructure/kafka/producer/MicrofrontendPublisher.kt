package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.budstikka.domain.formidling.Mikrofrontend
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.formidlingJson

/**
 * Domenets inngang for å styre synlighet av en mikrofrontend på Min side (B41). Kalleren avhenger av
 * dette – ikke av Kafka, topic eller meldingsformatet. Transport og destinasjon bindes i
 * [microfrontendPublisher] ved oppstart.
 */
fun interface MicrofrontendPublisher {
    suspend fun publish(microfrontend: Mikrofrontend)
}

fun microfrontendPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
): MicrofrontendPublisher =
    MicrofrontendPublisher { microfrontend ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = microfrontend.partisjonsnokkel,
                value = formidlingJson.encodeToString(microfrontend.toMessage()),
            ),
        )
    }

@Serializable
internal enum class MinSideAction {
    @SerialName("enable")
    ENABLE,

    @SerialName("disable")
    DISABLE,
}

@Serializable
internal data class MicrofrontendMessage(
    @SerialName("@action") val action: MinSideAction,
    val ident: String,
    @SerialName("microfrontend_id") val microfrontendId: String,
    val sensitivitet: String = "high",
    @SerialName("@initiated_by") val initiatedBy: String = "team-esyfo",
)

private fun Mikrofrontend.toMessage() =
    MicrofrontendMessage(
        action =
            when (this) {
                is MikrofrontendAktiver -> MinSideAction.ENABLE
                is MikrofrontendDeaktiver -> MinSideAction.DISABLE
            },
        ident = personident.value,
        microfrontendId = mikrofrontendId,
    )
