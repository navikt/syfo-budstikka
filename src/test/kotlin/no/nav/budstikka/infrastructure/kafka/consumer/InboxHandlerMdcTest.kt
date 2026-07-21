package no.nav.budstikka.infrastructure.kafka.consumer

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.budstikka.application.MdcKeys
import org.slf4j.LoggerFactory

/**
 * B45/B46: konsum-steget skal bære korrelasjons-iden (`eventId`) som strukturert MDC-felt, så
 * `| event_id="X"` i Loki dekker HELE hendelsesløpet — også konsum, ikke bare decide/poller/send.
 * Fanger den faktisk emitterte logglinjen via en logback [ListAppender] og inspiserer MDC/format.
 */
class InboxHandlerMdcTest :
    FunSpec({
        lateinit var appender: ListAppender<ILoggingEvent>
        lateinit var logbackLogger: Logger

        beforeTest {
            logbackLogger = LoggerFactory.getLogger(InboxMessageHandler::class.java) as Logger
            appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
        }

        afterTest {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }

        test("valid inbox log line carries eventId on MDC for cross-step correlation") {
            val eventId = "00000000-0000-0000-0000-000000000042"
            val handler = InboxMessageHandler(FakeInboxMessageRepository(), FakeDeadLetterRepository())
            val payload =
                """{"reference":"ref-1","content":{"type":"MicrofrontendEnable","personIdentifier":"12345678901","microfrontendId":"mf-1"}}"""

            handler.handleBatch(listOf(testRecord(value = payload, eventId = eventId)))

            val event = appender.list.single { it.formattedMessage.contains("Inbox message handled") }
            event.mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe eventId
        }

        test("dead-letter log line carries failureReason as structured field, no eventId in MDC") {
            val handler = InboxMessageHandler(FakeInboxMessageRepository(), FakeDeadLetterRepository())

            // Missing event-id header -> dead-letter; no eventId available to correlate on.
            handler.handleBatch(listOf(testRecord(value = "ugyldig", eventId = null)))

            val event = appender.list.single { it.formattedMessage.contains("dead-lettered") }
            event.formattedMessage shouldContain "failureReason=MISSING_EVENT_ID"
            event.mdcPropertyMap[MdcKeys.EVENT_ID].shouldBeNull()
        }
    })
