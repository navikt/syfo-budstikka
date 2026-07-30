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

Include a short receiver preflight that records the expected branch, full HEAD
commit, and complete worktree state. Require the receiving session to verify
all three before continuing and to stop if they differ. Use fresh output from:

```sh
git branch --show-current
git rev-parse HEAD
git status --short --branch --untracked-files=all
```

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
