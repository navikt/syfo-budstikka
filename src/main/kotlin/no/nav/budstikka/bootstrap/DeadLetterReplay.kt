package no.nav.budstikka.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.infrastructure.replay.DeadLetterReplayer
import no.nav.budstikka.infrastructure.replay.config.toDeadLetterReplayConfig

internal fun Application.replayDeadLettersIfEnabled() {
    try {
        val config =
            try {
                environment.config.toDeadLetterReplayConfig()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(
                    "Dead-letter replay configuration is invalid; replay skipped {}",
                    kv(MdcKeys.ERROR_TYPE, error.javaClass.simpleName),
                )
                return
            }
        if (!config.enabled) {
            return
        }
        val replayer: DeadLetterReplayer by dependencies
        log.info("Dead-letter replay starting")
        val result = runBlocking { replayer.replay(config.batchSize) }
        log.info(
            "Dead-letter replay completed {} {}",
            kv(MdcKeys.REPLAYED_COUNT, result.replayed),
            kv(MdcKeys.SKIPPED_COUNT, result.skipped),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.error("Dead-letter replay failed {}", kv(MdcKeys.ERROR_TYPE, error.javaClass.simpleName))
    }
}
