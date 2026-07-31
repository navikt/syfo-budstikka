---
name: handoff
description: Compact the current conversation into a handoff document for another agent to pick up.
argument-hint: "What will the next session be used for?"
disable-model-invocation: true
---

Write a handoff document summarising the current conversation so a fresh agent
can continue the work. Use this only at a real session seam or
context-pressure boundary, not as an automatic phase artifact. Same-flow
agent delegation uses a short, bounded task brief instead.

Save the document inside a private directory created under the user's OS
temporary directory — not in the current workspace and not directly at a
predictable shared temporary path. For example, use `mktemp -d` on Unix-like
systems.

When the current directory is inside a Git worktree with an existing `HEAD`,
include a receiver preflight that records the canonical repository path,
expected branch, full HEAD commit, complete short status including untracked
paths, and a fingerprint of all tracked and non-ignored untracked changes.
Require the receiving session to use the same shared worktree, rerun the
commands, and stop if any value differs. Use fresh output from:

```bash
set -euo pipefail
repository_root=$(git rev-parse --show-toplevel)
cd "$repository_root"
printf '%s\n' "$repository_root"
git branch --show-current
git rev-parse HEAD
git status --short --branch --untracked-files=all
bash .github/skills/handoff/scripts/worktree-fingerprint.sh
```

The bundled fingerprint script hashes the index and raw tracked and
Git-reportable untracked worktree state, including symlink targets without
following them. It fails closed with an explicit diagnostic for unmerged
indexes, tracked submodules, embedded Git repositories, and non-ignored special
filesystem entries. Do not produce a Git preflight when the script rejects the
current repository state; resolve the state or tell the user that an exact
handoff cannot be created.

For each referenced ignored or out-of-worktree artifact, record its canonical
path plus a fresh content hash or another exact expected-state check. Do not
fingerprint all ignored files; build output and caches are unrelated noise.

If `git rev-parse --is-inside-work-tree` or `git rev-parse --verify HEAD` does
not succeed, omit the commit-based Git preflight. Record the relevant absolute
working directory and artifact paths with exact expected-state checks instead.

Include a "suggested skills" section in the document, which suggests skills
that the agent should invoke.

Do not duplicate content already captured in other artifacts (specs, plans,
ADRs, context, issues, commits, diffs, or pull requests). Reference them by
path or URL instead. Durable team state stays in those artifacts; the handoff
is temporary and disposable.

Redact secrets and personally identifiable or sensitive information,
including API keys, tokens, passwords, names, national identity numbers, and
health information.

If the user passed arguments, treat them as a description of what the next
session will focus on and tailor the document accordingly.

After writing, print the document's canonical absolute path so the user can
give that exact path to the receiving session.
