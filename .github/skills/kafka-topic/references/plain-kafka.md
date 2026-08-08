---
description: "Plain Apache Kafka in Ktor — consumer/producer skeleton, SSL config from Nais env vars, commit strategy and Testcontainers. Read when the repository uses org.apache.kafka:kafka-clients directly."
---

# Plain Apache Kafka in a Ktor backend

Patterns for `org.apache.kafka:kafka-clients` in a Ktor app. Use only if this is the stack the repository is already on.

## Startup alongside the Ktor server

The `poll` loop runs in its own coroutine/thread, not in a route. Start it from a Ktor module and stop it on `ApplicationStopPreparing`.

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
                    logger.error("Permanent failure, sending to DLQ",
                        kv("topic", record.topic()), kv("offset", record.offset()), e)
                    dlqProducer.send(record.value(), e.message)
                }
            }
            consumer.commitSync()
        }
        consumer.close()
    }

    fun stop() { running = false }
}
```

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
    // consumer-specific:
    put("group.id", System.getenv("KAFKA_CONSUMER_GROUP_ID") ?: "syfo-budstikka-v1")
    put("auto.offset.reset", "earliest")
    put("enable.auto.commit", "false")
}
```

You can also read these via the Ktor `environment.config` if they are mirrored into `application.yaml` — but `System.getenv` directly is common for Kafka SSL, since Nais sets them as plain env vars.

## Testing

- Use the Testcontainers `KafkaContainer` for integration tests — not embedded Kafka (discontinued).
- Unit-test the processing logic separately from the Kafka client: inject a `ConsumerRecord` (or just the payload) directly into `prosesser(...)` without starting a consumer.
- Run `./gradlew test` and return the result to the active task.

```kotlin
@Test
fun `processes sykmelding_sendt and deduplicates a duplicate`() {
    val repo = InMemoryEventStore()
    val record = ConsumerRecord("teamsykefravar.sykmelding.v1", 0, 0L, "fnr", payload)
    prosesser(record, repo)
    prosesser(record, repo)   // duplicate
    assertEquals(1, repo.antallProsessert())
}
```
