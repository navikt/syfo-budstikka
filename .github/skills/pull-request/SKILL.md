---
name: pull-request
description: Bruk når en endring i navikt/syfo-budstikka skal opprettes eller oppdateres som pull request: «opprett PR», «oppdater PR», eller /pull-request etter grønt vertikalt snitt.
---

# Pull request

Pakk endringen i en PR som kan reviewes uten gjetting: tydelig tittel, riktig issue-kobling, ferskt verifiseringsbevis og eksplisitt risiko.

## Kontrakt for PR-en

1. **Tittel følger semantisk format:** `type(scope): kort beskrivelse`.
   - Bruk `/conventional-commit` for type/scope-regler.
   - **Ferdig når:** tittelen beskriver endringen presist på én linje.

2. **Body følger repoets mal:** `.github/PULL_REQUEST_TEMPLATE.md`.
   - Fyll ut: Beskrivelse, Endringer, Issue, Verifikasjon, Sjekkliste.
   - **Ferdig når:** alle relevante seksjoner er utfylt uten plassholdertekst.

3. **Issue-kobling er eksplisitt.**
   - Fullført arbeid: `Closes #NNN`
   - Delvis arbeid: `Relates to #NNN`
   - Epic-kobling ved behov: `Del av epic: #MMM`
   - **Ferdig når:** koblingen matcher faktisk scope.

4. **Verifikasjon er fersk og deterministisk.**
   - Bruk normalt `./gradlew build`.
   - Lim inn kommando + exit-kode i Verifikasjon-seksjonen.
   - **Ferdig når:** reviewer ser grønn gate direkte i PR-body.

5. **Risiko og reviewer-fokus er tydelig.**
   - Kall ut endringer i auth, PII/logg, Flyway, Kafka, API-kontrakt, NAIS `accessPolicy`/secrets/deploy.
   - Pek på ADR ved styrende beslutninger.
   - **Ferdig når:** reviewer vet hva som må kontrolleres nøye.

6. **Ingen sensitive data eksponeres.**
   - Ingen fnr, tokens, credentials eller annen PII i diff eller loggeksempler.
   - **Ferdig når:** PR-tekst og diff er fri for sensitive data.

## Opprett eller oppdater med gh CLI

```bash
gh pr create \
  --repo navikt/syfo-budstikka \
  --title "type(scope): beskrivelse" \
  --body-file <fil-med-body>
```

```bash
gh pr edit <nummer> --repo navikt/syfo-budstikka --title "..." --body-file <fil>
```

## Etter opprettelse

- Hold PR-body oppdatert hvis scope eller verifikasjon endres.
- Adresser review i nye commits på samme branch.
- Squash-merge når checks og godkjenninger er grønne.
