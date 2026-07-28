# Triage labels

Existing labels are authoritative. Do not create labels in this repository
change.

| Canonical role | Tracker mapping | Rule |
|---|---|---|
| `needs-triage` | — | No state write without a mapping |
| `needs-info` | — | No state write without a mapping |
| `ready-for-agent` | — | No state write without a mapping |
| `ready-for-human` | — | No state write without a mapping |
| `wontfix` | `wontfix` | May be used after explicit confirmation |

When a workflow needs an unmapped canonical role, stop before writing to the
tracker and return
`NEEDS_DECISION: missing label mapping for <role>`. Do not substitute a
comment, issue text, or approximate label. The team may establish the
recommended canonical label set later; only then should this table be updated
with explicit mappings.

These existing category labels may be used when they describe the issue:
`bug`, `documentation`, `duplicate`, `enhancement`, `good first issue`,
`help wanted`, `invalid`, `question`, `wontfix`, `dependencies`,
`github_actions`, and `java`.

Wayfinder additionally requires confirmed mappings for `wayfinder:map` and
`wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, and
`wayfinder:task`. These mappings are currently absent. Treat that as a safe
prerequisite: stop before creating a map or tickets, and do not auto-create or
approximate labels.
