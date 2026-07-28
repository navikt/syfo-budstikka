---
description: "Explains a Kafka consumer’s Ktor lifecycle, health signal, and NAIS-injected SSL environment variables. Read when configuring or operating a consumer."
---

# Ktor lifecycle and NAIS configuration

The Ktor server (`EngineMain` on Netty) and a Kafka consumer have independent
lifecycles in one process. Start `KafkaConsumer.poll` in its own
`Dispatchers.IO` coroutine or thread from a Ktor module, and stop it on
`ApplicationStopPreparing`; never start it from a request. See the complete
setup in [plain-kafka.md](plain-kafka.md).

The consumer heartbeat contributes to `/internal/health/is_alive`. Readiness at
`/internal/health/is_ready` currently covers the database. If a new consumer
must affect readiness, change that semantic explicitly. Keep `/internal/*`
without auth; see `/auth-overview`.

NAIS configuration:

```yaml
spec:
  kafka:
    pool: nav-dev   # or nav-prod
```

NAIS injects `KAFKA_BROKERS`, `KAFKA_TRUSTSTORE_PATH`, `KAFKA_KEYSTORE_PATH`,
and `KAFKA_CREDSTORE_PASSWORD`, plus `KAFKA_SCHEMA_REGISTRY*` when schema
registry is active. Read them through `System.getenv(...)` or
`environment.config`, and follow existing `infrastructure/kafka/config` rather
than creating a parallel configuration path.
