---
description: "Provides plain Apache Kafka patterns for Ktor: consumer and producer skeletons, NAIS SSL configuration, commit strategy, and Testcontainers. Read when the repository uses org.apache.kafka:kafka-clients directly."
---

# Plain Apache Kafka in a Ktor backend

Patterns for `org.apache.kafka:kafka-clients` in a Ktor application. Use only
when that is the repository’s existing stack.

## Start beside the Ktor server

The `poll` loop runs in a separate coroutine or thread, not in a route. Start it
from a Ktor module and stop it on `ApplicationStopPreparing`.

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

Wire it into the Ktor lifecycle from an Application module:

```kotlin
fun Application.kafkaConsumerModule(consumer: HendelseConsumer) {
    val job = launch(Dispatchers.IO) { consumer.run() }   // separate coroutine, not a route
    monitor.subscribe(ApplicationStopPreparing) {
        consumer.stop()                                    // set running=false and let the loop finish
        job.cancel()
    }
}
```

Commit strategy: `commitSync()` after every batch is safe and simple.
`commitAsync()` gives higher throughput; use it only when deliberate. Keep
`enable.auto.commit=false` so commits happen after successful processing, not before.

## Producer

```kotlin
producer.send(ProducerRecord(topic, key, value)) { _, exception ->
    if (exception != null) {
        logger.error("Failed to send to Kafka", kv("topic", topic), exception)
    }
}
```

For exactly-once-like semantics, use `enable.idempotence=true` and `acks=all`.
Use transactions (`initTransactions()`) only when coordinating produce and
consume-commit in the same application.

## Configuration from NAIS-injected environment variables

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

Read the repository’s Kafka configuration through Ktor `environment.config`
from `application.conf`. NAIS provides SSL files through environment variables;
follow existing `infrastructure/kafka/config` rather than introducing a parallel
`System.getenv` path.

## Testing

- Use Testcontainers `KafkaContainer` for integration tests, not deprecated embedded Kafka.
- Unit-test processing logic separately from the Kafka client: inject a
  `ConsumerRecord`, or only its payload, directly into `prosesser(...)` without
  starting a consumer.
- Run `./gradlew test` and return result and exit code in KOKK_RESULT/PR.

```kotlin
@Test
fun `processes sykmelding_sendt and deduplicates duplicate`() {
    val repo = InMemoryEventStore()
    val record = ConsumerRecord("teamsykefravar.sykmelding.v1", 0, 0L, "fnr", payload)
    prosesser(record, repo)
    prosesser(record, repo)   // duplicate
    assertEquals(1, repo.antallProsessert())
}
```
