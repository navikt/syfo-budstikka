package no.nav.budstikka.infrastructure.kafka.producer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.dispatch.Microfrontend
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.dispatchJson

fun microfrontendPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
): MicrofrontendPublisher =
    MicrofrontendPublisher { microfrontend ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = microfrontend.partitionKey,
                value = dispatchJson.encodeToString(microfrontend.toMessage()),
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
    @SerialName("@action")
    val action: MinSideAction,
    val ident: String,
    @SerialName("microfrontend_id")
    val microfrontendId: String,
    val sensitivitet: String = "high",
    @SerialName("@initiated_by")
    val initiatedBy: String = "team-esyfo",
)

private fun Microfrontend.toMessage() =
    MicrofrontendMessage(
        action =
            when (this) {
                is MicrofrontendEnable -> MinSideAction.ENABLE
                is MicrofrontendDisable -> MinSideAction.DISABLE
            },
        ident = personIdentifier.value,
        microfrontendId = mikrofrontendId,
    )
