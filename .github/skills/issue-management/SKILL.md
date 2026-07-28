---
name: issue-management
description: "Create, update, link, or close GitHub issues. Use when the user explicitly requests tracker work or Wayfinder needs confirmed issue mechanics; require confirmation before every external write."
---

# Issue management

Use the `gh` CLI and read `docs/agents/issue-tracker.md` and `triage-labels.md`
first. External writes require explicit user confirmation. If an action requires
a canonical state without a mapping, return `NEEDS_DECISION` before writing.

This is the **mechanics skill** for an issue lifecycle. It manages issue
structure, links, status, and closure after the caller has clarified the work.
It never starts implementation.

## Workflow

### 1. Check whether the issue already exists

Before creating an issue, check whether the user has already referenced one
(for example `#123` or a GitHub URL). If so, use it. Also inspect the active
brief and PR diff: the slice may already be tracked there.

### 2. Select a type

| Type | Use |
|------|------|
| **Epic** | Large effort split into several issues |
| **Feature** | New capability, such as an endpoint, Kafka consumer, or datastore |
| **Story** | User story or use case |
| **Task** | Technical work: Flyway migration, NAIS configuration, refactoring, upgrade, or chore |
| **Bug** | Defect to fix |

### 3. Create the issue with the right structure

If the repository has an issue template in `.github/ISSUE_TEMPLATE/` for the
chosen type, read its fields and create a Markdown body with matching sections.
Without a template, use a short fixed structure: **What**, **Why**,
**Acceptance criteria**, and the relevant backend layers (migration, route,
consumer, auth, test, NAIS).

Use domain language from `docs/glossary.md` in titles and descriptions where it
exists, and respect decisions in the relevant `docs/adr/` area.

Include where relevant:

- **Dependencies:** `Blocked by #NNN`
- **Epic link:** `Part of epic: #EPIC_NUMBER`

### 4. Create the issue

Use `gh api` to create the issue with the selected type:
```bash
gh api repos/navikt/syfo-budstikka/issues \
  -X POST \
  -f title="Persist received budstikka event" \
  -f body="BODY" \
  -f type="Task" \
  --jq '.html_url'
```

See `references/issue-types.md` for native types and how to discover those
available in the `navikt` organisation.

### 4b. Add the issue to a project board (when configured)

Creating an issue with `gh api` does not automatically add it to a project as
the web UI does. Run the project step afterwards:

- Read the repository’s issue template and look for a `projects:` line. If the
  template or line is missing, skip the project flow without failing.
- When a project is configured, add the issue and discover project, field, and
  option values dynamically. Never hard-code field IDs, option IDs, or statuses.

See `references/projects.md` for the sequence, auth preflight, and error handling.

### 5. Manage an epic

For work split into several issues:

1. Create the epic issue first.
2. Create independently actionable child issues from the confirmed plan.
3. Link sub-issues to the epic through GitHub’s sub-issues API (`references/sub-issues.md`).
4. Link dependencies through the dependencies API (`references/dependencies.md`).
5. Keep `Part of epic: #EPIC_NUMBER` and `Blocked by #NNN` in issue text for readability.

#### Sub-issues must stand alone

Each sub-issue must be actionable without reading the entire epic:

- Clear description of the work.
- Affected files and layers (`src/main/kotlin/no/nav/budstikka/...`, Flyway migration, NAIS manifest).
- Dependencies on other issues.
- Testable acceptance criteria (`./gradlew test`).

### 6. Select the next frontier

When inspecting an epic:

1. **Read the epic**: retrieve the epic, sub-issues, and dependency information.
2. **Categorise open sub-issues:**
   - **Runnable now**: every dependency is satisfied.
   - **Blocked**: at least one dependency remains open.
   - **Parallel candidates**: several runnable tasks have no mutual dependency.
3. **Present a recommendation:**
   - One candidate: recommend it.
   - Several candidates: offer selectable or parallel options.
   - No candidates: explain the blocker.
4. Return the selected issue to the current Barista or Grillmester route. Do
   not implement it from this skill or change project status merely because it
   was selected.

### 7. Completion note on issues

After resolving an issue, add a completion note. Obtain facts from KOKK_RESULT
and PR evidence:

```bash
gh issue comment ISSUE_NUMBER --repo navikt/syfo-budstikka --body "COMMENT_BODY"
```

Keep it structured and concise:

~~~markdown
## ✅ Resolved

**Summary:** [Short description of the work]

**Changed files:**
- `src/main/kotlin/no/nav/budstikka/...Kt` — [what changed]
- `src/main/resources/database.migration/VNN__...sql` — [Flyway migration]
- `nais/nais-dev.yaml` / `nais/nais-prod.yaml` — [topic / accessPolicy if relevant]

**Verification:** `./gradlew test` (+ build/lint) — [pass/fail + exit code, or `Not run` + reason]

**Inspection:** [Approved / Approved with notes / Follow-up required] — [short summary, optionally from grill-inspektor]

**PR:** #PR_NUMBER
~~~

Keep the inspection summary short; do not include a full report unless the user asks.

### 8. Propose epic closure

After closing a sub-issue, check whether every sub-issue in the epic is complete.
When none remain open, present the summary and ask for explicit confirmation
before commenting on or closing the epic.

### 9. Link issues in PRs

When work results in a PR, typically via `/pull-request` during delivery:

```
Closes #ISSUE_NUMBER
```

If the issue must remain open:

```
Relates to #ISSUE_NUMBER
```

`Closes #...` normally moves the issue to `Done` in Projects V2 automatically.
Do not add separate Done logic unless the board requires it; see `references/projects.md`.
