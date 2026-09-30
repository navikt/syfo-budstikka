package no.nav.budstikka.application.retention

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.budstikka.testsupport.structuredFields
import org.slf4j.LoggerFactory

class RetentionWorkerTest :
    FunSpec({
        test("reports completed cleanup counts to the metrics port") {
            val metrics = RecordingRetentionMetrics()
            val worker =
                RetentionWorker(
                    repository =
                        RetentionRepository {
                            RetentionResult.Completed(
                                RetentionCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4),
                            )
                        },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.completedCounts shouldBe
                listOf(RetentionCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4))
        }

        test("warns with counts only when the absolute ceiling deletes unprocessed inbox rows") {
            val counts = RetentionCounts(inboxMessages = 3, deadLetterMessages = 2, deliveries = 1, unprocessedInboxMessages = 2)
            val metrics = RecordingRetentionMetrics()
            val logbackLogger = LoggerFactory.getLogger(RetentionWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                RetentionWorker(RetentionRepository { RetentionResult.Completed(counts) }, 100, metrics).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            metrics.completedCounts shouldBe listOf(counts)
            with(appender.list.single { it.formattedMessage == "Retention cleanup completed" }) {
                level shouldBe Level.INFO
                structuredFields() shouldBe
                    mapOf(
                        "inbox_deleted" to 3,
                        "inbox_unprocessed_deleted" to 2,
                        "dead_letter_deleted" to 2,
                        "delivery_deleted" to 1,
                    )
            }
            with(appender.list.single { it.level == Level.WARN }) {
                formattedMessage shouldBe "Retention cleanup deleted unprocessed inbox rows past the absolute retention ceiling"
                structuredFields()["event_type"] shouldBe RetentionLogEvents.unprocessedInboxDeleted.name
                structuredFields()["inbox_deleted"] shouldBe 3
                structuredFields()["inbox_unprocessed_deleted"] shouldBe 2
                structuredFields().keys shouldBe
                    setOf("event_type", "operation", "inbox_deleted", "inbox_unprocessed_deleted")
                throwableProxy shouldBe null
            }
        }

        test("does not warn when no unprocessed inbox rows were deleted") {
            val logbackLogger = LoggerFactory.getLogger(RetentionWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                RetentionWorker(
                    RetentionRepository {
                        RetentionResult.Completed(RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0))
                    },
                    100,
                    RecordingRetentionMetrics(),
                ).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            appender.list.filter { it.level == Level.WARN }.shouldBeEmpty()
            appender.list.single().structuredFields()["inbox_unprocessed_deleted"] shouldBe 0
        }

        test("reports advisory-lock contention to the metrics port") {
            val metrics = RecordingRetentionMetrics()
            val worker =
                RetentionWorker(
                    repository = RetentionRepository { RetentionResult.SkippedDueToLockContention },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.lockContentions shouldBe 1
        }
    }) {
    private class RecordingRetentionMetrics : RetentionMetrics {
        val completedCounts = mutableListOf<RetentionCounts>()
        var lockContentions = 0

        override fun completed(counts: RetentionCounts) {
            completedCounts += counts
        }

        override fun lockContention() {
            lockContentions++
        }
    }
}
