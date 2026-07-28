---
description: "Provides the full Kafkarator Topic CRD template, including retention, cleanupPolicy, partitions, replication, ACLs, and provisioning choices. Read when creating or changing a topic."
---

# Kafkarator Topic-CRD

```yaml
apiVersion: kafka.nais.io/v1
kind: Topic
metadata:
  name: <team>.<domain>.v<version>
  namespace: <team>
  labels:
    team: <team>
spec:
  pool: nav-prod
  config:
    retentionHours: 168          # 7 days
    retentionBytes: -1           # unlimited
    cleanupPolicy: delete        # or "compact" for state topics
    minimumInSyncReplicas: 2
    partitions: 3
    replication: 3
  acl:
    - team: <team>
      application: <app>
      access: readwrite          # read | write | readwrite
    - team: <other-team>
      application: <consumer-app>
      access: read
```

Important choices:

- **cleanupPolicy: compact** for topics representing latest state per key. It
  requires a stable key.
- **partitions**: increase early; reducing requires a new topic. Start at 3–6
  for domain events.
- **acl**: explicit per consumer application, never wildcard.
