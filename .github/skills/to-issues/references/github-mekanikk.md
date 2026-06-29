# GitHub-mekanikk — typer, sub-issues, avhengigheter

Repo: `navikt/syfo-budstikka`. Foretrekk MCP-verktøy når de er tilgjengelige i aktivt toolset; fall tilbake til `gh api` ellers.

## Issue-typer

| Type | Bruk |
|------|------|
| **Epic** | Stor oppgave som brytes ned i flere issues |
| **Feature** | Ny funksjonalitet |
| **Story** | Brukerhistorie / brukstilfelle |
| **Task** | Teknisk oppgave, vedlikehold, chore (typisk for prefaktorering) |
| **Bug** | Feil som må fikses |

### MCP (primær)

- `list_issue_types` for å oppdage tilgjengelige typer før du setter verdi
- `issue_write` med `type`-parameter for oppretting/oppdatering

### Fallback (gh api)

```bash
# Opprett issue med type
gh api repos/navikt/syfo-budstikka/issues \
  -X POST \
  -f title="Kort, beskrivende tittel" \
  -f body="BODY" \
  -f type="Feature" \
  --jq '.html_url'

# List tilgjengelige org-typer
gh api graphql -H "GraphQL-Features: issue_types" \
  -f query='query { organization(login: "navikt") { issueTypes { nodes { id name } } } }'
```

## Epic + sub-issues

For store oppgaver:

1. Opprett epic-issuet først
2. Opprett de underliggende snittene (issues)
3. Koble dem som sub-issues til epicen native
4. Koble avhengigheter native (se under)
5. Behold `Del av epic: #EPIC_NR` og `Blokkert av #NNN` i issue-teksten for lesbarhet

Hvert sub-issue skal være selvstendig: nok kontekst til at noen kan plukke det uten å lese hele epicen (tydelig "hva som skal bygges", styrende ADR, avhengigheter, akseptansekriterier).

### MCP (primær)

`sub_issue_write` (default-enabled i issues-toolset) for å opprette, liste, fjerne og reprioritere sub-issues.

### Fallback (gh api)

```bash
# Legg til sub-issue (sub_issue_id er issue-NUMMER, ikke node-id)
gh api repos/navikt/syfo-budstikka/issues/{epic_number}/sub_issues \
  -X POST -f sub_issue_id=NUMBER

# List sub-issues
gh api repos/navikt/syfo-budstikka/issues/{epic_number}/sub_issues

# Reprioriter rekkefølge
gh api repos/navikt/syfo-budstikka/issues/{epic_number}/sub_issues \
  -X PATCH -f sub_issue_id=N -f after_id=M
```

## Avhengigheter (blocked-by / blocking)

Native avhengigheter erstatter tekstbasert `Blokkert av #NNN` som datakilde — behold likevel teksten i body for lesbarhet.

### Fallback (gh api)

```bash
# Issue er avhengig av et annet issue
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/dependencies \
  -X POST -f dependent_issue_id=N

# List avhengigheter
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/dependencies

# Fjern avhengighet
gh api repos/navikt/syfo-budstikka/issues/{issue_number}/sub_issues/dependencies/{dependency_id} -X DELETE
```

Semantikk: **blocked-by** = hvilke issues blokkerer dette; **blocking** = hvilke dette blokkerer.

## Epic-progresjon

Når en epic løses stegvis: hent epic + sub-issues + avhengigheter, og kategoriser åpne snitt som **kjørbar nå** (alle avhengigheter oppfylt), **blokkert** (minst én åpen avhengighet) eller **parallelle kandidater** (flere kjørbare uten innbyrdes avhengighet). Foreslå neste plukk deretter. Når alle sub-issues er lukket: oppsummer på epicen og lukk den.

## Ferdigmelding på issue

```bash
gh issue comment ISSUE_NR --repo navikt/syfo-budstikka --body "BODY"
```

Strukturert og kort: **Oppsummering**, **Endrede filer**, **Verifisering** (`./gradlew test`/build-output med exit-kode, eller `Ikke kjørt` + grunn), **PR** (`#NR`). Ingen full rapport med mindre brukeren ber om det.

## PR-kobling

`Closes #NNN` i PR-en for å lukke issuet automatisk, ev. `Relates to #NNN` for å holde det åpent.
