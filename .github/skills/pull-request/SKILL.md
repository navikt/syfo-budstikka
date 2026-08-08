---
name: pull-request
description: "Use when a change in this repository is to be created or updated as a pull request. Triggers: 'create a PR' / 'opprett PR' / 'lag en PR', 'update the PR' / 'oppdater PR-en', 'open a pull request' / 'åpne en pull request', or /pull-request after a green vertical slice."
---

# Pull request

Package the change in a PR that can be reviewed without guesswork: a clear title, the right issue link, fresh verification evidence and explicit risk.

## Contract for the PR

1. **The title follows the semantic format:** `type(scope): short description`.
   - Scopes in use in this repository: `kafka`, `db`, `nais`, `deps`, `auth`,
     `config`, `build`, `kontrakt`, `observability`, `logging`, and for the agent
     corpus `skills`, `agents`, `instructions`. Reuse an existing scope when one
     fits; the vocabulary is descriptive, not closed — `git log --oneline` is the
     source of truth. Omit the scope rather than inventing a one-off.
   - **Done when:** the title describes the change precisely on one line.

2. **The body follows the repository template:** `.github/PULL_REQUEST_TEMPLATE.md`.
   - Fill in: Beskrivelse, Endringer, Issue, Verifikasjon, Sjekkliste.
   - The template and its sections are Norwegian; use `/klarsprak` for the prose.
   - **Done when:** all relevant sections are filled in with no placeholder text.

3. **The issue link is explicit.**
   - Completed work: `Closes #NNN`
   - Partial work: `Relates to #NNN`
   - Epic link when needed: `Del av epic: #MMM`
   - **Done when:** the link matches the actual scope.

4. **Verification is fresh and deterministic.**
   - Normally use `./gradlew build`.
   - Paste the command + exit code into the Verifikasjon section.
   - **Done when:** the reviewer sees a green gate directly in the PR body.

5. **Risk and reviewer focus are clear.**
   - Call out changes to auth, PII/logging, Flyway, Kafka, the API contract, NAIS `accessPolicy`/secrets/deploy.
   - Describe task-relevant constraints briefly; follow the link rules in
     `docs/agents/domain.md` for any document links.
   - **Done when:** the reviewer knows what needs careful checking.

6. **No sensitive data is exposed.**
   - No national identity numbers, tokens, credentials or other PII in the diff or log examples.
   - **Done when:** the PR text and diff are free of sensitive data.

## Create or update with the gh CLI

```bash
gh pr create \
  --repo navikt/syfo-budstikka \
  --title "type(scope): short description" \
  --body-file <file-with-body>
```

```bash
gh pr edit <number> --repo navikt/syfo-budstikka --title "..." --body-file <file>
```

## After creation

- Keep the PR body updated if scope or verification changes.
- Address review feedback in new commits on the same branch.
- Squash-merge when checks and approvals are green.
