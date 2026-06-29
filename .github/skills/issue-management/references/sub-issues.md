# Native GitHub sub-issues

## MCP (primær)

Bruk MCP `sub_issue_write` for å opprette, liste, fjerne og reprioritere sub-issues. `sub_issue_write` er default-enabled i issues-toolset.

## Fallback (gh api)

Når MCP ikke er tilgjengelig, bruk GitHub REST via `gh api` (owner/repo = `navikt/syfo-budstikka`).

### Legg til sub-issue

```bash
gh api \
  repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues \
  -X POST \
  -f sub_issue_id=NUMBER
```

> `sub_issue_id` er issue-nummeret (integer), ikke node ID.

### List sub-issues

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues
```

### Fjern sub-issue

```bash
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/{sub_issue_id} -X DELETE
```

### Reprioriter rekkefølge

```bash
gh api \
  repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues \
  -X PATCH \
  -f sub_issue_id=N \
  -f after_id=M
```

### Notat

Dette erstatter tekstbasert `Del av epic: #NNN` som datakilde, men behold gjerne teksten i issue body for lesbarhet.
