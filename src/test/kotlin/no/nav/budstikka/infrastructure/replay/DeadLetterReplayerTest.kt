package no.nav.budstikka.infrastructure.replay

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.infrastructure.database.dispatch.ReplayableDeadLetter
import no.nav.budstikka.infrastructure.kafka.consumer.FakeDeadLetterRepository
import no.nav.budstikka.infrastructure.kafka.consumer.FakeInboxMessageRepository
import org.slf4j.LoggerFactory
import java.util.UUID

class DeadLetterReplayerTest :
    FunSpec({
        test("a parseable dead letter is replayed into inbox and deleted") {
            val deadLetter = replayable(payload = validPayload())
            val deadLetters = FakeDeadLetterRepository(mutableListOf(deadLetter))
            val inbox = FakeInboxMessageRepository()

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 1, skipped = 0)

            inbox.savedEvents.single().eventId shouldBe deadLetter.eventId
            deadLetters.findReplayable(limit = 100, offset = 0).shouldBeEmpty()
        }

        test("a dead letter that still cannot parse is retained and counted as skipped without logging payload") {
            val fnr = "31129956715"
            val deadLetter = replayable(payload = """{"personIdentifier":"$fnr" """)
            val deadLetters = FakeDeadLetterRepository(mutableListOf(deadLetter))
            val inbox = FakeInboxMessageRepository()
            val logger = LoggerFactory.getLogger(DeadLetterReplayer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 0, skipped = 1)
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            inbox.savedEvents.shouldBeEmpty()
            deadLetters.findReplayable(limit = 100, offset = 0) shouldBe listOf(deadLetter)
            appender.list.forEach { event ->
                event.formattedMessage shouldNotContain fnr
                (event.throwableProxy?.message ?: "") shouldNotContain fnr
            }
        }

        test("an empty replay batch does not write inbox rows or delete dead letters") {
            val calls = mutableListOf<String>()
            val deadLetters = FakeDeadLetterRepository(calls = calls)
            val inbox = FakeInboxMessageRepository(calls = calls)

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 0, skipped = 0)

            calls shouldBe listOf("find")
        }

        test("inbox rows are saved before their dead letters are deleted") {
            val calls = mutableListOf<String>()
            val deadLetters = FakeDeadLetterRepository(mutableListOf(replayable(payload = validPayload())), calls)
            val inbox = FakeInboxMessageRepository(calls = calls)

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100)

            calls shouldBe listOf("find", "save", "delete")
        }

        test("replaying twice does not duplicate inbox rows or fail") {
            val deadLetters = FakeDeadLetterRepository(mutableListOf(replayable(payload = validPayload())))
            val inbox = FakeInboxMessageRepository()
            val replayer = DeadLetterReplayer(deadLetters, inbox)

            replayer.replay(limit = 100) shouldBe ReplayResult(replayed = 1, skipped = 0)
            replayer.replay(limit = 100) shouldBe ReplayResult(replayed = 0, skipped = 0)

            inbox.savedEvents.size shouldBe 1
        }

        test("a payload with sendingWindow null is replayed") {
            val deadLetter = replayable(payload = validPayload(sendingWindow = "null"))
            val deadLetters = FakeDeadLetterRepository(mutableListOf(deadLetter))
            val inbox = FakeInboxMessageRepository()

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 1, skipped = 0)

            inbox.savedEvents.single().eventId shouldBe deadLetter.eventId
        }

        test("a replayable row after more unparseable rows than the batch size is replayed") {
            val deadLetters =
                FakeDeadLetterRepository(
                    mutableListOf(
                        replayable(payload = """{"personIdentifier":"11111111111" """),
                        replayable(payload = """{"personIdentifier":"22222222222" """),
                        replayable(payload = """{"personIdentifier":"33333333333" """),
                        replayable(payload = validPayload()),
                    ),
                )
            val inbox = FakeInboxMessageRepository()

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 2) shouldBe ReplayResult(replayed = 1, skipped = 3)

            inbox.savedEvents.size shouldBe 1
            deadLetters.findReplayable(limit = 100, offset = 0).size shouldBe 3
        }
    })

private fun replayable(payload: String): ReplayableDeadLetter =
    ReplayableDeadLetter(
        id = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        payload = payload,
    )

private fun validPayload(sendingWindow: String? = null): String =
    """
    {
      "reference": "ref-1",
      "content": {
        "type": "BrukervarselCreate",
        "personIdentifier": "12345678901",
        "varseltype": "BESKJED",
        "text": "Hei"${sendingWindow?.let { """, "sendingWindow": $it""" }.orEmpty()}
      }
    }
    """.trimIndent()
