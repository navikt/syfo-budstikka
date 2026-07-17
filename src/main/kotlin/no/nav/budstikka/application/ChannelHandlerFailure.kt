package no.nav.budstikka.application

import kotlinx.coroutines.CancellationException
import no.nav.budstikka.domain.decision.Channel

internal class ChannelHandlerFailure(
    channel: Channel,
    operation: String,
    cause: Throwable,
) : RuntimeException("${channel.name} channel failed while $operation", cause)

internal suspend fun <T> withChannelHandlerFailureContext(
    channel: Channel,
    operation: String,
    action: suspend () -> T,
): T =
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ChannelHandlerFailure(channel, operation, error)
    }
