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
not submit a form or treat the form as project configuration. Write
programmatic issue bodies in Norwegian and reuse the matching form's headings:
`Kort fortalt`, then the form's acceptance and context labels, for example
`Akseptansekriterier` or `Ferdig når`, and `Teknisk kontekst og bevis`. These
localized headings take precedence over generic skill examples such as
`In short` and `Acceptance criteria`. Write sections without a form
counterpart, such as `Utenfor scope`, in Norwegian as well.

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

The `projects` entry in `.github/ISSUE_TEMPLATE/` names the team board. The
board's fields, status options, and project workflows belong to the board and
change independently of this repository, so they are not described here.

- Read the board's fields and options at runtime, for example with
  `gh project field-list`, and match them by exact name. Never assume a status
  name, field name, or opaque ID.
- The forms' `projects` entry applies only to form-driven creation. For a
  programmatically created issue, check whether a project workflow already
  added it before adding it explicitly.
- Set a status or other field value only when the user or the task has
  established it. Never infer one; if no option matches the intended state,
  ask.
- Change project state only as part of an authorized write, not because an
  issue was inspected or selected.
- `gh project` commands require the `project` token scope. If `gh auth status`
  does not list it, stop before the first project call and ask the user to run
  `gh auth refresh -s project` or to explicitly skip the project step.
- Project workflows may add items or change fields on their own. After every
  project write, and after closing an issue, read the item back and verify its
  issue URL, project, and field values. Allow a short read-only retry before
  adding an item that a workflow may already have added. Missing or wrong
  project state is a partial failure handled by the stop-and-repair rule above.
- A terminal project status is tracker state, not proof that the work shipped;
  the issue's closure reason and final comment carry that distinction.

## Ready for implementation

Treat an issue as ready for implementation only after reading the complete
issue and verifying that it has:

- a clear goal and scope, including the nearest relevant non-goal;
- verifiable acceptance criteria;
- the current-state evidence, constraints, risks, and test seam needed by the
  implementer, without invented detail;
- correct native parent and blocking relationships; and
- confirmed issue type, project membership, and other explicitly chosen
  tracker metadata.

If one of these is materially missing, state what must be clarified; do not
manufacture readiness.
