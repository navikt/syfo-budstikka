# Issue dependencies

Use GitHub REST through `gh api` (owner/repo = `navikt/syfo-budstikka`). Get the
blocker’s database ID with `gh api repos/navikt/syfo-budstikka/issues/<n> --jq .id`;
the issue number and node ID are not valid dependency IDs.

### Add a dependency

```bash
# Issue N depends on another issue
gh api \
  repos/navikt/syfo-budstikka/issues/{issue_number}/dependencies/blocked_by \
  -X POST \
  -f issue_id=<blocker_database_id>
```

### List dependencies

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number} --jq .issue_dependencies_summary
```

### Remove a dependency

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/dependencies/{dependency_id} -X DELETE
```

### Semantics

- **blocked-by**: issues that block this issue
- **blocking**: issues that this issue blocks

### Note

This replaces text-based `Depends on #NNN` as the data source, but keep the text
in the issue body for readability.
