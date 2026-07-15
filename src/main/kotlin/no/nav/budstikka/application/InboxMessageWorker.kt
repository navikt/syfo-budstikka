package no.nav.budstikka.application

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.dispatchJson
import org.slf4j.LoggerFactory

/**
 * Beslutnings-workeren (#56): claimer mottatte inbox-meldinger (FOR UPDATE SKIP LOCKED + lease, ADR
 * 0004 — flere replicaer kan kjøre samtidig), dekoder payloaden og effektuerer utfallet per melding
 * via [EffectuateDecision] (delivery + inbox-status i én DB-tx).
 *
 * Beslutningen delegeres til [DecisionProcess], som ruter til policy per meldingstype og lar hver
 * policy hente sitt eget grunnlag (f.eks. PDL for isAlive-gaten).
 *
 * Workeren eier én runde ([runOnce]); selve løkke-livssyklusen (intervall, heartbeat, shutdown)
 * komponeres rundt den i bootstrap via `BackgroundLoop`. Lease-budsjett-draineringen deles med
 * outbox-workeren via [LeaseBudgetDrainer]: workeren slutter å starte nye meldinger når
 * budsjettandelen av leasen er brukt, så en treg batch ikke krysser leasen (og en peer re-enricher
 * samme melding). Uberørte claimede meldinger blir stående til leasen utløper.
 */
class InboxMessageWorker(
    private val repository: InboxMessageRepository,
    private val effectuator: EffectuateDecision,
    private val decisionProcess: DecisionProcess,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
    private val metrics: DispatchMetrics,
) {
    private val logger = LoggerFactory.getLogger(InboxMessageWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.eventId.toString() },
            claim = {
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts).also { claimed ->
                    if (claimed.isEmpty()) metrics.inboxEmptyPoll() else metrics.inboxClaimed(claimed.size)
                }
            },
            process = { message ->
                val decision = decideFor(message)
                effectuator.effectuate(message.eventId, decision)
                metrics.record(decision)
                logger.info("Message processed")
            },
        )
    }

    private fun DispatchMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> inboxProcessed()
            is Decision.Dropped -> inboxDropped(decision.reason)
            is Decision.Failed -> inboxFailed()
        }
    }

    private suspend fun decideFor(message: InboxMessage): Decision {
        logger.debug("Decoding inbox payload")
        val dispatch =
            try {
                dispatchJson.decodeFromString<Dispatch>(message.payload)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SerializationException) {
                return decodeFailedDecision(error)
            } catch (error: IllegalArgumentException) {
                return decodeFailedDecision(error)
            }
        return decisionProcess.process(dispatch)
    }

    private fun decodeFailedDecision(error: Throwable): Decision.Failed {
        // B46/PII: en decode-feil kan bære payload-innhold (fnr) i exception-meldingen — kotlinx
        // echo-er «JSON input: …». Logg/persister derfor KUN exception-TYPEN, aldri message eller
        // hele throwable. eventId er på MDC (korrelasjon bevart); rå payload slås ev. opp i
        // inbox_message under tilgangskontroll (B46-feilsøkingsmodell).
        val errorType = error.javaClass.simpleName
        logger.warn("Failed to decode inbox message {}", kv("errorType", errorType))
        return Decision.Failed("DECODE_FAILED: $errorType")
    }
}
