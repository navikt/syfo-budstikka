# Issue tracker

Work is tracked in GitHub Issues for `navikt/syfo-budstikka`. Pull requests are
delivery and review records, not the request surface.

- Search for an existing issue before creating one.
- Read the complete issue and comments before using it as a task contract.
- A pickable issue states its goal, scope, acceptance criteria, and
  dependencies.
- Link delivery with `Closes #NNN` or `Relates to #NNN` as appropriate.
- Creating, editing, assigning, labelling, commenting on, or closing an issue
  or pull request requires explicit human authorization.

Run `gh` from this clone so it resolves the repository from `origin`.

Prefer semantic GitHub tools when they expose the required operation. The
`gh issue edit` fallback supports native issue types, parents, sub-issues,
and dependency edges without hand-written REST payloads:

```sh
gh issue edit PARENT --add-sub-issue CHILD
gh issue edit ISSUE --add-blocked-by BLOCKER
gh issue edit ISSUE --type TYPE
gh issue edit ISSUE --add-assignee "@me"
gh issue edit ISSUE --remove-assignee "@me"
```

For native graph reads, use the equivalent semantic GitHub tool or:

```sh
gh issue view MAP --json number,title,state,subIssues
gh issue view TICKET --json number,title,state,assignees,blockedBy
```

Read the affected issues and relationships back after a write. Do not duplicate
parent or blocking graph state in standard body sections. Prose may explain a
non-obvious dependency rationale, but it never replaces the native relationship.

Stop on the first failed, ambiguous, or partial mutation. Inspect the exact
remote state before retrying; never silently create replacements, delete
partial state, or continue with a malformed graph. Present the smallest repair
set and obtain new authorization before applying it.

## Labels

Existing GitHub labels are authoritative. Query the repository before applying
a label, use its exact name, and apply it only with explicit human
authorization.

Do not create, guess, approximate, or silently omit a label a workflow asks
for. If a workflow requires a label that is not present in the repository, stop
before the tracker write and ask for a decision. A missing label is a safe
prerequisite, never authority to invent one.

## Issue types

The supported NAV issue types are `Bug`, `Epic`, `Feature`, `Story`, and
`Task`. Preserve the triage workflow's semantic mapping to those names. Before
setting a type, query the organization through the available semantic GitHub
tool or GitHub's `organization.issueTypes` GraphQL field and verify that the
exact name is still enabled; a missing type is a decision stop.

```sh
gh api graphql -f 'query=query { organization(login: "navikt") { issueTypes(first: 20) { nodes { name } } } }' --jq '.data.organization.issueTypes.nodes[].name'
```

## Wayfinder

Wayfinder uses one map issue with native sub-issues as decision tickets and
native dependencies as blocking edges. Assignment to the authenticated user
(`@me` with `gh`) is the claim for a session. Verify assignment after claiming
or releasing it. The frontier is the map's open, unassigned children with no
open blocker.

GitHub assignment is not an exclusive session lock: it permits multiple
assignees and cannot distinguish two sessions using the same account. This
pilot therefore has one coordinating Wayfinder session at a time. Researcher
agents may run in parallel inside that session, but independent
ticket-resolution sessions must be serialized.

Immediately before a claim, re-read the ticket, its blockers, and assignees;
stop if it is no longer open, unblocked, and unassigned. Immediately after the
claim, verify that the authenticated user is the sole assignee. Treat any
competing assignee as a conflict: do no ticket work, then propose an authorized
release of the local claim.

Before editing the map body, re-read it and apply only the intended change to
that fresh body while preserving all other observed state. Read it back after
the write. A mismatch is an ambiguous partial failure handled by the common
stop-and-repair rule above.

The pilot preserves these exact upstream label identities:

| Label | Meaning |
|---|---|
| `wayfinder:map` | Canonical low-resolution decision map |
| `wayfinder:research` | AFK fact-finding ticket |
| `wayfinder:prototype` | HITL ticket that needs a concrete artifact |
| `wayfinder:grilling` | HITL decision conversation |
| `wayfinder:task` | Prerequisite work that unlocks a decision |

Query the repository before charting a map. If any required label is absent,
stop before creating or editing issues and ask the user whether to create the
missing labels. Authorization to run Wayfinder is not by itself authorization
to create repository labels.

## Projects

No GitHub Project mapping is configured for this repository. Do not add issues
to a project or invent project fields or statuses.
