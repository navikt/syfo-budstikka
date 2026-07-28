---
name: prototype
description: "Build a throwaway prototype to answer a design question. Use when the team wants to sanity-check a data model, state machine, API shape, Kafka flow, or other uncertain behavior."
---

# Prototype

A prototype is disposable code that answers **one** design question. In this
backend repository, normally choose the logic track: a small terminal program
or test that drives a model, API sketch, or Kafka sequence through difficult cases.

1. State the question at the top of the prototype file and in the task brief.
2. Put code near the explored area but clearly outside production code, for
   example `src/test/kotlin/no/nav/budstikka/prototype/`.
3. Keep state in memory, provide one execution command, and show all relevant
   state after every action. Do not use a shared database, Kafka cluster, or real auth.
4. Add no polish, speculative abstraction, or production promises.
5. Preserve useful runnable evidence outside the main branch as a primary
   source, normally on a task-scoped throwaway branch linked from the issue.
   Creating a commit, branch, push, or issue link still requires the repository's
   normal explicit authority; until then, leave the prototype local and do not
   delete it without confirmation.
6. Record the validated answer separately in the task-scoped brief, issue/PR,
   glossary, or ADR according to what is durable. The prototype is evidence,
   not the decision. If its value remains unclear, wait for the user's decision
   before choosing a direction or retaining it.
