---
description: "Plain Apache Kafka in Ktor — consumer/producer skeleton, SSL config from Nais env vars, commit strategy and Testcontainers. Read when the repository uses org.apache.kafka:kafka-clients directly."
---

# Plain Apache Kafka in a Ktor backend

Patterns for `org.apache.kafka:kafka-clients` in a Ktor app. Use only if this is the stack the repository is already on.

## Startup alongside the Ktor server

The `poll` loop runs in its own coroutine/thread, not in a route. In this repository consumers are `ConsumerRunner`s registered in Ktor DI (`infrastructure/kafka/config/Module.kt`), started from bootstrap via `startKafkaConsumers()` (`bootstrap/KafkaConsumers.kt`), and torn down by the DI `.cleanup { }` block — `close()` joins the loop with a 5 s timeout (`CLOSE_TIMEOUT_SECONDS = 5` in `ConsumerRunner.kt`). Do not wire teardown through `monitor.subscribe(ApplicationStopPreparing)`.

```kotlin
class HendelseConsumer(private val consumer: KafkaConsumer<String, String>, private val topic: String) {
    @Volatile private var running = true

    fun run() {
        consumer.subscribe(listOf(topic))
        while (running) {
            val records = consumer.poll(Duration.ofMillis(1000))
            records.forEach { record ->
                try {
                    prosesser(record)
                } catch (e: MidlertidigFeil) {
                    throw e   // let Kafka redeliver on the next poll
                } catch (e: PermanentFeil) {
                    logger.error("Permanent failure, parking as dead letter",
                        kv("topic", record.topic()), kv("offset", record.offset()), e)
                    deadLetterRepository.saveBatch(listOf(record.toDeadLetter(e)))   // Postgres row, not a DLQ topic
                }
            }
            consumer.commitSync()
        }
        consumer.close()
    }

    fun stop() { running = false }
}
```

Dead letters are Postgres rows in `dead_letter_message` (`DeadLetterMessageRepository.kt`), replayed manually via `DeadLetterReplayer` behind `DEAD_LETTER_REPLAY_ENABLED` — see `docs/dead-letter-replay.md`. There is no DLQ topic and no DLQ producer in this repository.

Commit strategy: `commitSync()` after each batch is safe and simple. `commitAsync()` gives higher throughput — use it only when there is a deliberate need. Keep `enable.auto.commit=false` so that you commit after successful processing, not before.

## Producer

```kotlin
producer.send(ProducerRecord(topic, key, value)) { _, exception ->
    if (exception != null) {
        logger.error("Failed to send to Kafka", kv("topic", topic), exception)
    }
}
```

For exactly-once-like semantics: `enable.idempotence=true` and `acks=all`. Transactions (`initTransactions()`) only when you coordinate produce + consume commit in the same app.

## Configuration from Nais-injected env vars

```kotlin
val props = Properties().apply {
    put("bootstrap.servers", System.getenv("KAFKA_BROKERS"))
    put("security.protocol", "SSL")
    put("ssl.truststore.type", "PKCS12")
    put("ssl.truststore.location", System.getenv("KAFKA_TRUSTSTORE_PATH"))
    put("ssl.truststore.password", System.getenv("KAFKA_CREDSTORE_PASSWORD"))
    put("ssl.keystore.type", "PKCS12")
    put("ssl.keystore.location", System.getenv("KAFKA_KEYSTORE_PATH"))
    put("ssl.keystore.password", System.getenv("KAFKA_CREDSTORE_PASSWORD"))
    // consumer-specific — from the typed HOCON config, not System.getenv:
    put("group.id", consumerConfig.groupId)   // default "syfo-budstikka-budstikka-v1", override KAFKA_BUDSTIKKA_GROUP_ID
    put("auto.offset.reset", "earliest")
    put("enable.auto.commit", "false")
}
```

In this repository all of these — including the SSL values — are read via Ktor `environment.config` from `application.conf` (HOCON), where `${?KAFKA_BROKERS}`-style substitutions pick up the Nais-injected env vars into a typed config (`infrastructure/kafka/config/Config.kt`, `PropertiesFactory.kt`). The consumer group id defaults to `syfo-budstikka-budstikka-v1` (`kafka.consumers.budstikka.groupId`, application.conf) and is overridden with `KAFKA_BUDSTIKKA_GROUP_ID`. Do not call `System.getenv` directly.

## Testing

- Use the Testcontainers `KafkaContainer` for integration tests — not embedded Kafka (discontinued).
- Unit-test the processing logic separately from the Kafka client: build a `ConsumerRecord` with the event-id header (see `testRecord(...)` in `src/test/kotlin/.../consumer/ConsumerTestUtils.kt`) and feed it to the handler without starting a consumer.
- Tests are Kotest `FunSpec` — never JUnit annotations. Follow the idiom in `InboxHandlerTest.kt` (`createTestContext()` from `TestSetup.kt`, fake repositories, `shouldBe`):

```kotlin
class InboxHandlerTest :
    FunSpec({
        test("record without the event-id header is dead-lettered, not saved") {
            with(createTestContext()) {
                handler.handle(testRecord(value = payload, eventId = null))

                inboxRepository.savedEvents.size shouldBe 0
                deadLetterRepository.savedDeadLetters.single().failureReason shouldBe "MISSING_EVENT_ID"
            }
        }
    })
```

- Run `./gradlew test` and return the result to the active task.
