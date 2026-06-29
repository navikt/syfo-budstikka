---
applyTo: "src/main/kotlin/**/*.kt"
description: "Plain Apache Kafka i Ktor — consumer/producer-skjelett, SSL-config fra Nais-env, commit-strategi og Testcontainers. Les når repoet bruker org.apache.kafka:kafka-clients direkte."
---

# Plain Apache Kafka i Ktor-backend

Mønstre for `org.apache.kafka:kafka-clients` i en Ktor-app. Bruk kun hvis det er stacken repoet allerede står på.

## Oppstart ved siden av Ktor-serveren

`poll`-løkka kjører i en egen coroutine/tråd, ikke i en route. Start den fra en Ktor-modul og stopp den på `ApplicationStopPreparing`.

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
                    throw e   // la Kafka re-levere ved neste poll
                } catch (e: PermanentFeil) {
                    logger.error("Permanent feil, sender til DLQ",
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

Commit-strategi: `commitSync()` etter hver batch er trygt og enkelt. `commitAsync()` gir høyere throughput — bruk kun ved bevisst behov. Hold `enable.auto.commit=false` så du committer etter vellykket prosessering, ikke før.

## Producer

```kotlin
producer.send(ProducerRecord(topic, key, value)) { _, exception ->
    if (exception != null) {
        logger.error("Feil ved sending til Kafka", kv("topic", topic), exception)
    }
}
```

For exactly-once-lignende semantikk: `enable.idempotence=true` og `acks=all`. Transaksjoner (`initTransactions()`) kun når du koordinerer produce + consume-commit i samme app.

## Konfigurasjon fra Nais-injiserte env vars

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
    // consumer-spesifikt:
    put("group.id", System.getenv("KAFKA_CONSUMER_GROUP_ID") ?: "syfo-budstikka-v1")
    put("auto.offset.reset", "earliest")
    put("enable.auto.commit", "false")
}
```

Du kan også lese disse via Ktor `environment.config` hvis de er speilet inn i `application.yaml` — men `System.getenv` direkte er vanlig for Kafka-SSL siden Nais setter dem som rene env vars.

## Testing

- Bruk Testcontainers `KafkaContainer` for integrasjonstester — ikke embedded Kafka (avviklet).
- Unit-test prosesseringslogikken separat fra Kafka-klienten: injiser en `ConsumerRecord` (eller bare payloaden) direkte i `prosesser(...)` uten å starte en consumer.
- Kjør `./gradlew test` og loggfør resultatet i `.grill/VERIFICATION.md`.

```kotlin
@Test
fun `prosesserer sykmelding_sendt og dedup-er duplikat`() {
    val repo = InMemoryEventStore()
    val record = ConsumerRecord("teamsykefravar.sykmelding.v1", 0, 0L, "fnr", payload)
    prosesser(record, repo)
    prosesser(record, repo)   // duplikat
    assertEquals(1, repo.antallProsessert())
}
```
