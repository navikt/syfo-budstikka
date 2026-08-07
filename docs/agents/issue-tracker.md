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
- Adding an issue to a project or changing a project field is a separate
  tracker mutation and must be included in that authorization.

## Human-readable issue contract

Issues serve product leads, designers, developers, and implementation agents.
Use progressive detail: the title and opening provide functional orientation;
the remaining issue provides enough evidence and technical context to act
without rediscovering the task.

- Use a functional title that names the observable change or problem. Do not
  lead with a filename, component, technology, or implementation mechanism
  unless that mechanism is itself the outcome.
- Start the body with `## Kort fortalt`: one or two plain-language sentences
  that say who or what benefits, what changes, and why it matters.
- Use a user story only when a real actor and value are clearer as “Som …
  ønsker jeg … slik at …”. Do not force chores, refactors, or platform work
  into that form.
- Put acceptance criteria next. Follow with the current-state evidence,
  constraints, risks, test seams, and technical context needed to make the
  issue independently actionable. Prefer precise links or small examples over
  a speculative implementation walkthrough.
- Keep implementation walkthroughs, file inventories, decision history, and
  native parent or dependency state out of the opening.

The forms in `.github/ISSUE_TEMPLATE/` are the manual entry point for this
contract. Programmatic creation follows the same layered structure; it does
not submit a form or treat the form as project configuration. The localized
heading `Kort fortalt` takes precedence over generic skill examples such as
`In short`.

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

The team board is [Team eSyfo](https://github.com/orgs/navikt/projects/157):

- owner: `navikt`
- project number: `157`

Resolve project, field, item, and option IDs at runtime. The number and the
exact semantic names below are configuration; opaque IDs are not.

Manual issues created through this repository's forms use
`projects: ["navikt/157"]`. GitHub applies that field only to form-driven
creation and only when the author has the required project access.
Programmatically created issues must therefore be added to the project
explicitly and verified.

GitHub forms cannot choose a project field value. The project's enabled `Item
added to project` workflow owns initial status for that path and must set
`Backlog`. Smoke-test one issue after changing the forms or project workflow;
if it lands elsewhere, treat that as a configuration failure. Independently of
the smoke test, triage must read and verify status before an intake issue
becomes pickable.

### Status mapping

| Workflow state | Exact `Status` option |
|---|---|
| Created for later prioritization | `Backlog` |
| Approved and independently pickable | `Plukk meg! 🙌` |
| Work has started | `Jeg jobbes med! ⚒️` |
| Waiting for team discussion or a team-owned decision | `Til diskusjon` |
| Closed or otherwise terminal | `Done` |

### Ready for implementation

`Backlog` may contain incomplete intake. Set `Plukk meg! 🙌` only after reading
the complete issue and verifying that it has:

- a clear goal and scope, including the nearest relevant non-goal;
- verifiable acceptance criteria;
- the current-state evidence, constraints, risks, and test seam needed by the
  implementer, without invented detail;
- correct native parent and blocking relationships; and
- confirmed issue type, project membership, and other explicitly chosen
  tracker metadata.

If one of these is materially missing, keep the issue in `Backlog` or use `Til
diskusjon` when a team-owned decision is the blocker. State what must be
clarified; do not manufacture readiness.

For a newly approved set of implementation issues, put open unblocked issues
in `Plukk meg! 🙌` and blocked issues in `Backlog`, unless the user chose
another configured state. If implementation starts in the current session,
set only the issue actually being started to `Jeg jobbes med! ⚒️`; other
unblocked slices remain pickable. Do not change existing project state merely
because an issue was inspected or selected.

`Sommer epics 🎯` and `AID-oppdraget` are special planning lanes, not fallback
statuses. Set them only when the user explicitly chooses one. Never infer
`Priority`, `Size`, `Estimate`, `Tertial`, `Måleparameter`, or `Tags`; propose
only a value established by the task and include it in the authorized write
set.

### Project operations

Prefer semantic GitHub project tools when they expose the complete operation.
With `gh`, the minimum write path is:

```sh
gh project item-add 157 --owner navikt --url ISSUE_URL --format json
gh project field-list 157 --owner navikt --format json
gh project item-edit --id ITEM_ID --project-id PROJECT_ID \
  --field-id STATUS_FIELD_ID --single-select-option-id STATUS_OPTION_ID
```

Capture the item ID returned by `item-add`. For an existing item, query by the
exact issue URL and paginate until the item is found or the project is
exhausted; never treat the first 30 or 100 items as the whole board. Match the
`Status` field and option by the exact configured names above.

After every add or field mutation, read the item back and verify its issue URL,
project, and field value. A created issue with missing or wrong project state
is a partial failure: preserve the issue, stop further writes, present the
smallest repair, and obtain authorization before repairing it.

Closing an issue normally lets the project's enabled built-in workflow set
`Done`. This is a terminal tracker state, not proof that the issue shipped; the
issue's closure reason and final comment carry that distinction. Read the
project item back after closing. If it is not `Done`, report the mismatch and
propose an explicit authorized status repair rather than assuming the
automation is enabled.

Wayfinder adds the map to Team eSyfo as `Til diskusjon`. Project 157 currently
has `Auto-add sub-issues to project` enabled, so attaching native decision
tickets can also add them to the board. Include that automatic effect in the
authorized write set, then verify each child before continuing. Because the
workflow is asynchronous, allow a short bounded read-only retry before treating
an absent item as a partial failure. Keep writes stopped while polling, and do
not call `item-add` again when the workflow already added the child.

An unclaimed decision ticket starts in `Backlog`; a ticket claimed for the
current session uses `Jeg jobbes med! ⚒️`; a ticket waiting for team discussion
uses `Til diskusjon`; and the enabled close workflow sets terminal tickets to
`Done`. These items remain decision work, not implementation slices, even when
the project workflow makes them visible on the board.
