---
name: resolving-merge-conflicts
description: "Resolve an in-progress Git merge or rebase conflict by tracing each side to primary sources. Use when an update stops on conflicts or conflict markers."
---

# Resolve merge conflicts

Resolve an active merge or rebase conflict in this repository. Do not use
`--abort` by default; resolve the conflict.

## Workflow

1. **Map the state.** Identify the operation and colliding files.
   ```bash
   git status              # merge or rebase? which files are "both modified"?
   git log --oneline --graph -15
   git diff                # inspect conflict markers in context
   ```
   Rebase reverses the meaning of `HEAD` (= the branch you rebase **onto**) and
   `incoming` (= your own commit). Check direction before choosing a side.

2. **Find the primary source for each conflict.** Understand why each side
   changed the code, not only what the hunk says.
   ```bash
   git log --merge -p -- <file>    # commits on both sides touching the file
   git blame <fil>
   ```
   Read commit messages, PRs, and issues. In this domain, intention is often not
   visible in the diff: auth (TokenX/Azure AD), NAIS `accessPolicy`, a Flyway
   migration, or a Kafka consumer can have a reason documented only in the PR.

3. **Resolve every hunk.** Preserve both intents where possible. When they are
   incompatible, choose the one matching the merge's stated goal and record the
   trade-off. Do not invent behaviour. Be especially careful with:
   - **Flyway migrations** (`src/main/resources/database.migration/`): when two
     branches add `V<n>__...sql` with the same number, rename them to consecutive,
     unique versions — do not combine their contents into one file. Never modify
     an already-run migration; add a new one.
   - **`gradle/libs.versions.toml` / `build.gradle.kts`**: keep the newest
     compatible version, not both lines. Check that the Ktor BOM/`ktorLibs` and
     Kotlin versions remain coherent.
   - **NAIS YAML** (`nais/`): merge `accessPolicy`, environment variables, and
     scaling as sets — retain entries from both sides.
   - **Kotlin imports and DI/routing** (`Application.kt`, modules): retain both
     new routes/plugins and avoid duplicate `install(...)` calls.

4. **Run project checks** and fix what the merge broke.
   ```bash
   ./gradlew build         # compilation + test
   ./gradlew test          # faster when only logic changed
   ```
   Repeat until green. A merge that compiles but fails tests means two correct
   changes are logically incompatible; resolve that semantically, not by deleting a test.

5. **Complete the operation.**
   ```bash
   git add -A
   git commit              # merge: keep or edit the generated message
   # or, during rebase:
   git rebase --continue   # repeat steps 1–4 for each remaining commit
   ```
   During a rebase, resolve conflicts per commit; repeat the loop as needed.

## Safety net

- Before a large or risky merge, `git merge --no-commit --no-ff <branch>` lets
  you inspect before committing.
- Losing orientation during a rebase is not failure, but this workflow completes
  the operation. Run `git rebase --abort` only when the user requests it.
- Use `git checkout --ours <file>` or `--theirs <file>` only when a file clearly
  comes entirely from one side (for example generated files). Remember that
  “ours” and “theirs” are reversed during a rebase.
