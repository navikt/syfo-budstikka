# GitHub Projects V2

## When is this needed?

The web UI can automatically add an issue to a project through `projects:` in
the issue template. Creating with `gh api` does not. Run the project step after
creating the issue.

## Runtime discovery of the project

1. Read the relevant issue template: `.github/ISSUE_TEMPLATE/<type>.yml`.
2. Find a `projects:` line in `["owner/number"]` form (for example
   `["navikt/157"]` → `owner=navikt`, `number=157`).
3. Parse the owner and project number from that line.
4. If template or line is missing, the repository has no configured project link;
   skip the entire project flow without failing.

## Minimum sequence for project linking

0. **Auth preflight before project calls**
   Run `gh auth status` and look for the `project` scope before using `gh project ...`.
   - If `project` scope is missing, stop before the first `gh project` call and show:
     "To update the project board, I need the `project` scope on your `gh` token. Run:

     ```
     gh auth refresh -s project
     ```

     Then I can continue. Would you rather skip project linking for now?"
   - If the user chooses to skip, skip the project step quietly and continue the issue flow.
1. **Find the project ID**
   Use `gh project list --owner OWNER --format json` and select the project whose
   `number` matches the template. Keep both project number and project ID.
2. **Add the issue to the project**
   Use `gh project item-add NUMBER --owner OWNER --url ISSUE_URL --format json`
   and retain the returned item ID.
3. **Discover fields dynamically**
   Use `gh project field-list NUMBER --owner OWNER --format json`. Find fields
   such as `Status` and optionally `Type` by name, then read their options from
   the response. Field and option IDs are project-specific: never hard-code them.
4. **Set initial field values**
   Use `gh project item-edit` with the item ID, project ID, discovered field ID,
   and option ID for single-select fields.
   - **Status**: prefer `Todo`, then `Backlog`, otherwise skip.
   - **Type**: set only when the project has that field and an option matching the issue type.

If a field is absent from the board, skip only that field, not the entire creation.

## Status transition when work starts

When the user selects an issue and asks to start work:

0. Run the same auth preflight before the first `gh project` call. If the user
   skips it, skip project work quietly and continue.
1. Run the same runtime discovery of the `projects:` line.
2. Find the existing item via `gh project item-list NUMBER --owner OWNER --format json`
   and match on issue URL. `item-list` defaults to 30 items; use `--limit 100`
   for large projects or paginate when needed.
3. If the issue is not in the project, run `item-add` first.
4. Retrieve field metadata again using `field-list`.
5. Update `Status` by matching known synonyms first: `In Progress`, `Doing`,
   `Påbegynt`, `I arbeid`, `Under arbeid`. If none match, select the nearest
   active, non-`Done` option.

Do not assume that all boards use the same status names. Match the options in
the concrete project.

## Error handling

- Missing `projects:` line: skip the project step quietly.
- Project not found through `gh project list`: report briefly and continue; the issue already exists.
- Auth or permission error in `gh project`: report briefly and continue; do not roll back the issue.
- Missing field or option: skip only that field.
- `gh issue close` or a PR with `Closes #...` normally moves an issue to `Done`
  in Projects V2. Do not add separate Done logic unless the board requires it.
  This assumes the project’s built-in `Item closed` workflow is active (the
  default in new projects, but it can be disabled in Settings → Workflows).
