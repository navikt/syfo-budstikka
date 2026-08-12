package no.nav.budstikka.infrastructure.kafka.producer

import no.nav.budstikka.application.delivery.MicrofrontendPublisher
import no.nav.budstikka.contract.Microfrontend
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
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
