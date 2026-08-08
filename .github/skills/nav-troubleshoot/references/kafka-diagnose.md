# Kafka diagnosis — consumer lag and processing failures

Diagnostic trees for NAIS Kafka consumers in this repository's Ktor backend (`no.nav.syfo`). **Detect the actual stack first** — the app may use plain Apache Kafka clients (Kotlin) or Rapids & Rivers. The diagnosis must match what the code path actually runs.

See `/kafka-topic` for the patterns; this file is for *diagnosing when they do not work*.

## Check the consumer

```bash
# Logs filtered on Kafka-related messages
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
Consumer lag is growing
├── Is the consumer up?
│   ├── No → see pod-diagnose.md (CrashLoopBackOff?)
│   └── Yes → continue
├── Is the lag growing continuously or sporadically?
│   ├── Sporadically → normal traffic variation, probably OK
│   └── Continuously → the consumer cannot keep up
│       ├── Check the processing time per message (log/trace per record)
│       ├── Poison pill? — one message that always fails, blocking the offset commit
│       ├── Consider increasing `replicas` (up to the number of partitions)
│       └── Consider increasing the partitions on the topic (requires coordination)
├── Is the consumer logging errors?
│   ├── Deserialization error → schema mismatch, check the producer and the Avro/JSON schema
│   ├── DB error along the way → see database-diagnose.md
│   ├── Rebalance loop → check `max.poll.interval.ms` vs. the actual processing time
│   └── No errors but no progress → check that it is actually reading the right topic / consumer group
├── SSL / connectivity?
│   ├── "SSL handshake failed" → check that `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` are mounted and read
│   ├── "Connection refused" to `KAFKA_BROKERS` → check that `kafka.pool` is set in the manifest
│   └── Env vars missing → NAIS injects these; nothing works without `kafka.pool`
└── Rapids & Rivers (only if the app actually uses it)?
    ├── Check that the `validate { ... }` rules match the message format
    ├── `demandValue("@event_name", ...)` wrong value → messages are silently filtered away
    ├── `requireKey("...")` missing field → message rejected
    ├── Missing `interestedIn("...")` → the field is `null` in `packet["..."]`
    └── Log rejected messages to gain visibility
```

## Common NAV-specific failure patterns

| Observation | Cause | Fix |
|-------------|-------|---------|
| Lag grows linearly from a deploy | New `group.id` / offset reset = earliest | Controlled; it will catch up. Optionally set `auto.offset.reset: latest` if that is acceptable |
| The consumer processes nothing, no errors | Wrong `group.id` / topic name | Check the NAIS manifest and the Ktor config |
| Rebalance every N minutes | Processing time > `max.poll.interval.ms` | Reduce the batch size, or increase the interval |
| `SSL handshake failed` | The NAIS SSL env vars are not used correctly | Check that the app reads `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` / `KAFKA_CREDSTORE_PASSWORD` |
| Rapids messages "disappear" | `demandValue("@event_name", "...")` does not match | Log what the River actually sees; check the producer event name |

## Rapids & Rivers — debugging tips

Rapids & Rivers filters messages **silently** via `validate`. To gain visibility:

- Temporarily add `interestedIn("@event_name")` and log in `onPacket` / `onError` to see what actually gets through.
- Verify that the producer sends `@event_name` in the same snake_case spelling (`vedtak_fattet`, not `vedtakFattet`).
- Check that the River uses the same topic(s) the producer writes to (the rapids topic is often shared across the team).

## When this points elsewhere

- Pod crash → [pod-diagnose.md](./pod-diagnose.md)
- DB errors in the consumer → [database-diagnose.md](./database-diagnose.md)
- Auth errors on downstream calls from the consumer → [auth-diagnose.md](./auth-diagnose.md)
- Fix discipline (replaying a captured record through the handler) → `/diagnosing-bugs`
