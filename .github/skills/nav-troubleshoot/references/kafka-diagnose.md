# Kafka diagnosis — consumer lag and processing failures

Diagnosis trees for NAIS Kafka consumers in this Ktor backend (`no.nav.budstikka`).
**Detect the actual stack first**: the application uses plain Apache Kafka
clients (Kotlin), but verify the code path before fixing the diagnosis.

See `/kafka-topic` for patterns; this file is for *diagnosing when they do not work*.

## Check the consumer

```bash
# Logs filtered for Kafka-related messages
kubectl logs -n {namespace} -l app={app-name} --tail=200 \
  | grep -i "kafka\|consumer\|producer\|topic\|offset\|partition\|rebalance"

# Check for errors
kubectl logs -n {namespace} -l app={app-name} --tail=500 \
  | grep -i "error\|exception\|failed\|rejected"

# Check whether it is actually processing
kubectl logs -n {namespace} -l app={app-name} --tail=50 \
  | grep -i "processed\|consumed\|committed"

# Prometheus metrics (via port-forward)
kubectl port-forward -n {namespace} svc/{app-name} 8080:8080
# curl -s localhost:8080/internal/metrics | grep kafka
# kafka_consumer_fetch_manager_records_lag_max
# kafka_consumer_fetch_manager_records_consumed_total
```

## Diagnostic tree — Kafka consumer lag

```
Consumer lag is increasing
├── Is the consumer running?
│   ├── No → see pod-diagnose.md (CrashLoopBackOff?)
│   └── Yes → continue
├── Is lag increasing continuously or intermittently?
│   ├── Intermittently → normal traffic variation, probably OK
│   └── Continuously → the consumer cannot keep up
│       ├── Check processing time per message (log/trace per record)
│       ├── Poison pill? — one message that always fails, blocking offset commits
│       ├── Consider increasing `replicas` (up to the number of partitions)
│       └── Consider increasing topic partitions (requires coordination)
├── Is the consumer logging errors?
│   ├── Deserialization error → schema mismatch; check producer and Avro/JSON schema
│   ├── DB error during processing → see database-diagnose.md
│   ├── Rebalance loop → check `max.poll.interval.ms` against actual processing time
│   └── No errors but no progress → check that it reads the correct topic / consumer group
├── SSL / connectivity?
│   ├── "SSL handshake failed" → check that `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` are mounted and read
│   ├── "Connection refused" to `KAFKA_BROKERS` → check that `kafka.pool` is set in the manifest
│   └── Missing env vars → NAIS injects these; they do not work without `kafka.pool`
└── Rapids & Rivers (only if the app actually uses it)?
    ├── Check that `validate { ... }` rules match the message format
    ├── Incorrect `demandValue("@event_name", ...)` value → messages are silently filtered out
    ├── Missing field for `requireKey("...")` → message rejected
    ├── Missing `interestedIn("...")` → field is `null` in `packet["..."]`
    └── Log rejected messages to gain visibility
```

## Common NAV-specific failure patterns

| Observation | Cause | Resolution |
|-------------|-------|---------|
| Lag increases linearly from deploy | New `group.id` / offset reset = earliest | Expected; it will catch up. Optionally set `auto.offset.reset: latest` if acceptable |
| Consumer processes nothing, no errors | Incorrect `group.id` / topic name | Check the NAIS manifest and Ktor config |
| Rebalance every N minutes | Processing time > `max.poll.interval.ms` | Reduce batch size or increase the interval |
| `SSL handshake failed` | NAIS SSL env not used correctly | Check that the app reads `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` / `KAFKA_CREDSTORE_PASSWORD` |
| Rapids messages "disappear" | `demandValue("@event_name", "...")` does not match | Log what the River actually sees; check the producer event name |

## Rapids & Rivers — debugging tips

Rapids & Rivers filters messages **silently** through `validate`. To gain visibility:

- Temporarily add `interestedIn("@event_name")` and log in `onPacket` / `onError` to see what actually passes through.
- Verify that the producer sends `@event_name` using the same snake_case spelling (`vedtak_fattet`, not `vedtakFattet`).
- Check that the River uses the same topic(s) that the producer writes to (the rapids topic is often shared by a team).

## When this points elsewhere

- Pod crash → [pod-diagnose.md](./pod-diagnose.md)
- DB error in the consumer → [database-diagnose.md](./database-diagnose.md)
- Auth error in a downstream call from the consumer → [auth-diagnose.md](./auth-diagnose.md)
- Fix discipline (replay a captured record through the handler) → `/diagnosing-bugs`
