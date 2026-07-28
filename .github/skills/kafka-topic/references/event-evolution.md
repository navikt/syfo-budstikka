---
description: "Decision tree for changing an existing event: adding or removing fields, breaking formats, dual-write, and new event types. Read when changing an event contract."
---

# Event evolution — decision tree

```
How should an existing event change?
├── Add a new field (optional)
│   └── Backward compatible. Consumers must tolerate unknown fields
│       (tolerant parsing / interestedIn), not require them.
│
├── Change field format (breaking)
│   └── Create topic version v2. Dual-write from the producer.
│       Migrate consumers one at a time. Stop v1 production last.
│
├── Remove a field
│   └── 1. Verify that no consumer requires it.
│       2. Remove it from the producer. 3. Wait and observe before topic cleanup.
│
└── Add a new event type
    └── Publish with a new @event_name. Existing consumers ignore unknown
        event_names, particularly Rapids consumers.
```
