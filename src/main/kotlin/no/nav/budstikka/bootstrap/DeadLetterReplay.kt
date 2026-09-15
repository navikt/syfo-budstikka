package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.infrastructure.replay.DeadLetterReplayer
import no.nav.budstikka.infrastructure.replay.config.toDeadLetterReplayConfig

internal fun Application.replayDeadLettersIfEnabled() {
    val logger = applicationLogger()
    try {
        val config =
            try {
                environment.config.toDeadLetterReplayConfig()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.event(
                    BootstrapLogEvents.replayConfigurationInvalid,
                    FailureTypeContext(error.javaClass.simpleName),
                )
                return
            }
        if (!config.enabled) {
            return
        }
        val replayer: DeadLetterReplayer by dependencies
        logger.info("Dead-letter replay starting")
        val result = runBlocking { replayer.replay(config.batchSize) }
        logger.info(
            "Dead-letter replay completed",
            mapOf(
                MdcKeys.REPLAYED_COUNT to result.replayed,
                MdcKeys.SKIPPED_COUNT to result.skipped,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logger.event(
            BootstrapLogEvents.replayFailed,
            FailureTypeContext(error.javaClass.simpleName),
        )
    }
}
