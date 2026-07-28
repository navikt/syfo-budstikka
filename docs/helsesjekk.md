# Kafka consumer health check

## Summary

A Kafka consumer belongs in **liveness** (`is_alive`), not **readiness** (`is_ready`).
Use a **self-reported heartbeat** value, never a broker ping.

## Readiness versus liveness

| Probe | Question | Kafka consumer? |
| --- | --- | --- |
| `is_ready` | Can the pod receive traffic now? | **No.** The consumer reads a topic and serves no HTTP traffic. A dead consumer must not remove the pod from load balancing. |
| `is_alive` | Is the pod irrecoverably broken and due for restart? | **Yes.** A stuck or terminated consumer loop is a valid restart signal. |

## Pattern: self-reported heartbeat

1. The consumer loop updates a heartbeat timestamp on every poll round.
2. The liveness check reports unhealthy only when the latest poll is older than a threshold.
3. `is_alive` returns 503 when stale; Kubernetes restarts the pod.

```
consumer-loop:  poll() → recordPoll() → handle(records)   (every round)
is_alive:       is lastPoll fresh?  → 200 : 503
```

## Rules that make it work

- **Update on empty poll rounds too.** The heartbeat means “the loop is running,” not
  “messages arrived.” A quiet topic must not look dead.
- **A crashed or terminated loop must stop updating the heartbeat.** Catching and
  continuing after every exception keeps it ticking and liveness never fires, which
  defeats the check.
- **Set the threshold above poll frequency plus maximum processing time.** Otherwise a
  slow but healthy batch triggers a false restart. About five minutes is a safe default
  for low-volume topics.
- **Never couple liveness to broker availability.** A short Kafka outage would restart
  every pod simultaneously and turn a blip into an outage.
- **Never couple liveness to consumer lag.** Lag belongs in metrics and alerts; restarting
  a lagging pod makes the lag worse.

## Implementation notes (when a consumer arrives)

- Use `AtomicReference<Instant>` as the state holder: the consumer coroutine writes and
  the HTTP handler reads, with no lock required.
- Inject a `Clock` so the threshold is unit-tested with a fake clock (Kotest, without
  Testcontainers).
- Keep liveness state as a pure no-I/O unit and connect it to the `is_alive` route.

## Anti-patterns to avoid

- Pinging the broker in `is_ready` or `is_alive`.
- Putting the consumer in the readiness check.
- Letting the heartbeat continue after the loop is dead.
- Treating consumer lag as a liveness failure.
