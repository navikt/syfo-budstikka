# Issue tracker: GitHub

Issues and specifications for `navikt/syfo-budstikka` live in GitHub Issues.
Run `gh` from this clone; the CLI derives the repository from `origin`. Pull
requests are delivery and review records, not the triage entry point.

## Conventions

- Read with `gh issue view <number> --comments`; search for an existing issue
  first.
- Create, comment, label, assign, or close only after explicit human
  confirmation of the external state change.
- A pickable issue has a goal, scope, acceptance, dependencies, and links to
  relevant ADRs. Use `implementation-brief.md` for one implementation slice.
- Link a pull request with `Closes #NNN` or `Relates to #NNN`; include a fresh
  command, relevant result, and exit code.
- `/issue-management` owns confirmed GitHub mechanics. Barista or Grillmester
  clarifies ordinary work; `/wayfinder` owns its explicit multi-session
  decision map. Do not use pull requests as the request surface.

## Wayfinding operations

`/wayfinder` is a manual, explicit opt-in for dependent decisions that need a
route across multiple sessions; ordinary work uses a brief or specification.
Before any write, the required Wayfinder labels must exist and be mapped in
`triage-labels.md`; otherwise return
`NEEDS_DECISION: wayfinder label mapping is missing` without writing. A missing
mapping is a safe prerequisite, not authority to create, approximate, or
silently omit labels.

- Create the map, then child issues, then native dependency edges in a separate
  pass.
- A ticket is **frontier** when it is open, unblocked, and unassigned. Claim it
  with `gh issue edit <n> --add-assignee @me` only after human confirmation.
- Use native GitHub sub-issues and dependencies. If unavailable, stop and ask
  for a decision; do not invent an ad-hoc tracker format.
- Resolve with a resolution comment, close the ticket, and add a linked
  one-line conclusion to the map's "Decisions so far" section.
