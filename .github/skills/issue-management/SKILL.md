---
name: issue-management
description: Create, update, link, inspect, or close GitHub issues after the work has been shaped. Use for explicit tracker requests and native issue mechanics; do not use it to design, implement, or decompose a plan.
---

# Issue Management

Apply GitHub issue mechanics to work that is already understood. The caller owns
grilling, planning, specifications, and ticket decomposition; this skill owns
the resulting tracker mutations.

## 1. Load the adapter

Read the repository's issue-tracker adapter before using tracker tools. It
defines the repository, authorization boundary, available labels, issue types,
project mapping, and native relationship operations.

Stop before writing when a required mapping or operation is absent. Never guess
a label, approximate a relationship in prose, invent a project status, or
create a parallel tracker.

## 2. Inspect before drafting

Resolve a referenced number or URL and read the complete issue and comments.
Search for an existing issue before proposing a new one. For an epic, inspect
its native children and dependency graph rather than inferring state from a
list of links in prose.

## 3. Draft the smallest useful change

Use the repository's issue template when one exists. Otherwise keep the issue
self-contained and concise:

```markdown
## Goal

<observable outcome>

## Acceptance criteria

- [ ] <verifiable result>
```

Add context, non-goals, dependency rationale, rollback, or risk only when the
issue needs them to be independently actionable. Do not copy an umbrella
specification, decision history, file inventory, or implementation walkthrough
into every child issue.
Keep parent and dependency graph state in native relationships rather than
duplicating it in body sections.

Select only issue types, labels, assignees, parents, dependencies, and project
fields that the adapter establishes.

## 4. Confirm and write

Present the exact issues and mutations first. Obtain explicit human
authorization for that bounded set of external writes.

Prefer the available semantic GitHub tools. When using `gh`, use its native
issue operations, including:

- `gh issue edit PARENT --add-sub-issue CHILD`
- `gh issue edit ISSUE --add-blocked-by BLOCKER`
- `gh issue edit ISSUE --type TYPE`

Use repository and account context from the adapter. Verify every created or
changed issue after writing and report partial failure without silently
substituting a text-only relationship.

## 5. Maintain lifecycle without taking over delivery

Use native sub-issues and dependencies as the graph. Recommend the first open,
unblocked child when the user asks for the frontier; selection does not start
implementation or mutate project state.

Link delivery through the repository's PR convention. Comment on or close an
issue only when explicitly requested or included in the authorized delivery.
When an epic has no open children, propose its summary and closure; never close
it automatically.
