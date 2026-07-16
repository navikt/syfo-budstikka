package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.dispatch.Microfrontend
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.infrastructure.config.PlatformConfig
import no.nav.tms.microfrontend.MicrofrontendMessageBuilder

fun microfrontendPublisher(
    topic: String,
    messagePublisher: MessagePublisher,
    platformConfig: PlatformConfig,
): MicrofrontendPublisher =
    MicrofrontendPublisher { microfrontend ->
        messagePublisher.publish(
            PublishedMessage(
                topic = topic,
                id = microfrontend.partitionKey,
                value = microfrontend.toMessage(platformConfig).text(),
            ),
        )
    }

private fun Microfrontend.toMessage(platformConfig: PlatformConfig) =
    when (this) {
        is MicrofrontendEnable ->
            MicrofrontendMessageBuilder.enable(
                ident = personIdentifier.value,
                microfrontendId = microfrontendId,
                initiatedBy = platformConfig.namespace,
            )
        is MicrofrontendDisable ->
            MicrofrontendMessageBuilder.disable(
                ident = personIdentifier.value,
                microfrontenId = microfrontendId,
                initiatedBy = platformConfig.namespace,
            )
    }
