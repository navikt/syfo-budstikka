package no.nav.budstikka.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.TestApplication
import io.ktor.util.logging.KtorSimpleLogger
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.dispatch.ReplayableDeadLetter
import no.nav.budstikka.infrastructure.kafka.consumer.FakeInboxMessageRepository
import no.nav.budstikka.infrastructure.replay.DeadLetterReplayer
import org.slf4j.LoggerFactory
import java.util.UUID

class DeadLetterReplayTest :
    FunSpec({
        test("a replay failure is logged without payload and does not prevent application startup") {
            val payload = """{"personIdentifier":"31129956715" """
            val logger = LoggerFactory.getLogger("no.nav.budstikka.bootstrap.DeadLetterReplayTest.replayFailure") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val testApplication =
                TestApplication {
                    environment {
                        config =
                            MapApplicationConfig(
                                "deadLetterReplay.enabled" to "true",
                                "deadLetterReplay.batchSize" to "100",
                            )
                        log = KtorSimpleLogger(logger.name)
                    }
                    application {
                        dependencies {
                            provide {
                                DeadLetterReplayer(
                                    ThrowingDeadLetterRepository(payload),
                                    FakeInboxMessageRepository(),
                                )
                            }
                        }
                        replayDeadLettersIfEnabled()
                    }
                }
            try {
                runCatching { testApplication.start() }.isSuccess shouldBe true
            } finally {
                testApplication.stop()
                logger.detachAppender(appender)
                appender.stop()
            }

            val event = appender.list.single { it.formattedMessage.contains("Dead-letter replay failed") }
            event.formattedMessage shouldContain "IllegalStateException"
            event.formattedMessage shouldNotContain payload
            event.throwableProxy shouldBe null
        }

        test("disabled replay does not use the repository or log a start message") {
            val repository = RecordingDeadLetterRepository()
            val logger = LoggerFactory.getLogger("no.nav.budstikka.bootstrap.DeadLetterReplayTest.disabledReplay") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val testApplication =
                TestApplication {
                    environment {
                        config =
                            MapApplicationConfig(
                                "deadLetterReplay.enabled" to "false",
                                "deadLetterReplay.batchSize" to "100",
                            )
                        log = KtorSimpleLogger(logger.name)
                    }
                    application {
                        dependencies {
                            provide {
                                DeadLetterReplayer(
                                    repository,
                                    FakeInboxMessageRepository(),
                                )
                            }
                        }
                        replayDeadLettersIfEnabled()
                    }
                }
            try {
                runCatching { testApplication.start() }.isSuccess shouldBe true
            } finally {
                testApplication.stop()
                logger.detachAppender(appender)
                appender.stop()
            }

            repository.calls shouldBe emptyList()
            appender.list.any { it.formattedMessage.contains("Dead-letter replay starting") } shouldBe false
        }

        test("invalid replay configuration does not prevent startup or run replay") {
            val repository = RecordingDeadLetterRepository()
            val payload = """{"personIdentifier":"31129956715"}"""
            val logger = LoggerFactory.getLogger("no.nav.budstikka.bootstrap.DeadLetterReplayTest.invalidConfiguration") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val testApplication =
                TestApplication {
                    environment {
                        config =
                            MapApplicationConfig(
                                "deadLetterReplay.enabled" to "true",
                                "deadLetterReplay.batchSize" to "abc",
                            )
                        log = KtorSimpleLogger(logger.name)
                    }
                    application {
                        dependencies {
                            provide {
                                DeadLetterReplayer(
                                    repository,
                                    FakeInboxMessageRepository(),
                                )
                            }
                        }
                        replayDeadLettersIfEnabled()
                    }
                }
            try {
                runCatching { testApplication.start() }.isSuccess shouldBe true
            } finally {
                testApplication.stop()
                logger.detachAppender(appender)
                appender.stop()
            }

            repository.calls shouldBe emptyList()
            val event = appender.list.single { it.formattedMessage.contains("configuration is invalid") }
            event.formattedMessage shouldContain "IllegalStateException"
            event.formattedMessage shouldNotContain payload
            event.formattedMessage shouldNotContain "deadLetterReplay.batchSize must be a positive integer"
            event.throwableProxy shouldBe null
        }
    })

private class ThrowingDeadLetterRepository(
    private val payload: String,
) : DeadLetterMessageRepository {
    override suspend fun saveBatch(records: List<DeadLetterRecord>) = Unit

    override suspend fun findReplayable(
        limit: Int,
        offset: Long,
    ): List<ReplayableDeadLetter> = throw IllegalStateException(payload)

    override suspend fun deleteByIds(ids: List<UUID>) = Unit
}

private class RecordingDeadLetterRepository : DeadLetterMessageRepository {
    val calls = mutableListOf<String>()

    override suspend fun saveBatch(records: List<DeadLetterRecord>) {
        calls += "saveBatch"
    }

    override suspend fun findReplayable(
        limit: Int,
        offset: Long,
    ): List<ReplayableDeadLetter> {
        calls += "findReplayable"
        return emptyList()
    }

    override suspend fun deleteByIds(ids: List<UUID>) {
        calls += "deleteByIds"
    }
}
