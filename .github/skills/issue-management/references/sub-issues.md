# Native GitHub sub-issues

Use GitHub REST through `gh api` (owner/repo = `navikt/syfo-budstikka`) to
create, list, remove, and reprioritise sub-issues.

### Add a sub-issue

```bash
gh api \
  repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues \
  -X POST \
  -f sub_issue_id=NUMBER
```

> `sub_issue_id` is the issue number (integer), not a node ID.

### List sub-issues

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues
```

### Remove a sub-issue

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/{sub_issue_id} -X DELETE
```

### Reprioritise order

```bash
gh api \
  repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues \
  -X PATCH \
  -f sub_issue_id=N \
  -f after_id=M
```

### Note

This replaces text-based `Part of epic: #NNN` as the data source, but keep the
text in the issue body for readability.
