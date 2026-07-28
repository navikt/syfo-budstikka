---
description: "Provides NAIS JVM resource starting points, the rationale for no CPU limit, and the relationship between replicas, Hikari pools, and Postgres. Read when changing resources or scaling."
---

# Resources — JVM and Postgres

## No CPU limit

Never set `resources.limits.cpu`; set only `requests.cpu`. Kubernetes CFS quota
enforces CPU limits in 100 ms windows. When a container briefly reaches the
limit, it throttles threads that do not need CPU for the rest of the window.
This is particularly harmful during JVM startup (JIT, class loading, and GC)
and produces latency tails and timeouts. NAIS uses `requests.cpu` for scheduling
and lets the node handle actual consumption.

A memory limit must always be set: without it, a container can bring down a node with OOM.

| Size | `requests.cpu` | `requests.memory` | `limits.memory` |
|---|---:|---:|---:|
| Small | 50m | 256Mi | 512Mi |
| Medium | 100m | 512Mi | 1Gi |
| Large | 200m | 1Gi | 2Gi |

The JVM needs headroom above heap for metaspace, threads, and direct buffers.
Adjust from actual Grafana use; `replicas: { min: 2, max: 4,
cpuThresholdPercentage: 80 }` is a possible production starting point.

## Hikari and replicas

Pool sizing belongs to `/postgresql-review`: normally use `maximumPoolSize` 3–5
in containers, not the default 10, and align `maxLifetime`. The manifest is part
of the calculation: `replicas.max × maximumPoolSize ≤ max_connections`. More
replicas or a smaller `gcp.sqlInstances` tier tightens the pool budget.
