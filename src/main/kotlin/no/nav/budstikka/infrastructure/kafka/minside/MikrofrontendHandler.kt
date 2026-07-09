package no.nav.budstikka.infrastructure.kafka.minside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.budstikka.domain.formidling.Mikrofrontend
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.formidlingJson
import no.nav.budstikka.infrastructure.kafka.producer.OutboundMessage
import no.nav.budstikka.infrastructure.kafka.producer.ProducerHandler

@Serializable
internal enum class MinSideAction {
    @SerialName("actionEnabled")
    ENABLED,

    @SerialName("actionDisabled")
    DISABLED,
}

@Serializable
internal data class MicrofrontendMessage(
    @SerialName("@action") val action: MinSideAction,
    val ident: String,
    @SerialName("microfrontend_id") val microfrontendId: String,
    val sensitivitet: String = "high",
    @SerialName("@initiated_by") val initiatedBy: String = "team-esyfo",
)

object MikrofrontendHandler : ProducerHandler<Mikrofrontend> {
    override fun handle(value: Mikrofrontend): OutboundMessage =
        OutboundMessage(
            id = value.partisjonsnokkel,
            value = formidlingJson.encodeToString(value.toMessage()),
        )
}

private fun Mikrofrontend.toMessage() =
    MicrofrontendMessage(
        action =
            when (this) {
                is MikrofrontendAktiver -> MinSideAction.ENABLED
                is MikrofrontendDeaktiver -> MinSideAction.DISABLED
            },
        ident = personident.value,
        microfrontendId = mikrofrontendId,
    )
