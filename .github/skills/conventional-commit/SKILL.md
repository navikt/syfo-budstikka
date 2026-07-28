---
name: conventional-commit
description: "Write a precise Conventional Commit message from staged changes. Use when a commit is explicitly authorized or the user asks for commit wording."
---

# Conventional commits

Generate Conventional Commit messages appropriate to this repository.

## Format

```text
<type>(<scope>): <description>

[optional body]

[optional footer]
```

## Types

| Type | Use for |
|---|---|
| `feat` | New functionality |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, imports, ktlint; no code behavior |
| `refactor` | Code that is neither a fix nor feature |
| `perf` | Performance change |
| `test` | Adding or repairing tests |
| `build` | Gradle, dependencies, or container build |
| `ci` | GitHub Actions/workflow changes |
| `chore` | Other non-runtime work |

Choose a scope for the affected technical area, such as `routing`, `auth`,
`kafka`, `db`, `flyway`, `plugin`, `config`, `nais`, or `deps`. Use a package or
domain name such as `budskap` when that is more precise.

## Breaking changes

Add `!` after scope and explain the consumer impact in a `BREAKING CHANGE:`
footer.

```text
feat(routing)!: change budskap endpoint response format

BREAKING CHANGE: `opprettet` changed from epoch milliseconds to ISO-8601.
Consumers must update deserialization.
```

## Rules

- Keep the first line at most 72 characters.
- Use imperative wording; English is the default for commit messages unless a
  Norwegian domain term is the precise name.
- Do not end the subject with a period.
- Reference the GitHub issue in a footer when applicable: `Closes #123` or
  `Fixes #456`.
- Do not add automatic AI/agent attribution or generated trailers.

## Workflow

1. Inspect `git diff --cached --stat` and `git diff --cached`.
2. Identify type, scope, and a concise description.
3. Commit only when authorized by the repository delivery policy:

   ```bash
   git commit -m "type(scope): concise description" \
     -m "Optional explanation when needed."
   ```

4. When staged work contains unrelated logical changes, propose separate commits;
   use `git add -p` only with permission.

Before committing, stop and notify the user if staged work contains tokens, API
keys, credentials, passwords, PII, real NAIS secrets, or `.env` files.
