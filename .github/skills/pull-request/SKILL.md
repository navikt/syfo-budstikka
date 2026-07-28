---
name: pull-request
description: "Create or update a pull request for navikt/syfo-budstikka. Use when the user explicitly asks to create or update a PR after a verified slice."
---

# Pull request

Package the change in a PR that can be reviewed without guessing: a clear title,
correct issue link, fresh verification evidence, and explicit risk.

## PR contract

1. **Use a semantic title:** `type(scope): short description`.
   - Use `/conventional-commit` for type and scope rules.
   - **Done when:** the title describes the change precisely in one line.

2. **Follow the repository template:** `.github/PULL_REQUEST_TEMPLATE.md`.
   - Complete Description, Changes, Issue, Verification, and Checklist.
   - **Done when:** every relevant section is complete with no placeholder text.

3. **Link the issue explicitly.**
   - Completed work: `Closes #NNN`
   - Partial work: `Relates to #NNN`
   - Epic link when needed: `Part of epic: #MMM`
   - **Done when:** the link matches the actual scope.

4. **Keep verification fresh and deterministic.**
   - Normally use `./gradlew build`.
   - Paste the command and exit code into the Verification section.
   - **Done when:** the reviewer sees the green gate directly in the PR body.

5. **Make risk and reviewer focus clear.**
   - Call out changes to auth, PII/logging, Flyway, Kafka, API contracts, and
     NAIS `accessPolicy`, secrets, or deployment.
   - Link governing ADRs.
   - **Done when:** the reviewer knows what needs closer inspection.

6. **Expose no sensitive data.**
   - Include no fnr, tokens, credentials, or other PII in the diff or log examples.
   - **Done when:** both PR text and diff are free of sensitive data.

## After an explicit request to create or update this specific PR

Otherwise, draft the title and body, then ask for confirmation before running
either command.

```bash
gh pr create \
  --repo navikt/syfo-budstikka \
  --title "type(scope): description" \
  --body-file <file-with-body>
```

```bash
gh pr edit <number> --repo navikt/syfo-budstikka --title "..." --body-file <file>
```

## After creation

- Keep the PR body current when scope or verification changes.
- Address review in new commits on the same branch.
- Never merge from this skill. A merge requires a separate explicit user
  request; if granted, use squash only after checks and approvals are green.
