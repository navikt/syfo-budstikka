---
name: wayfinder
description: "Map a multi-session, decision-heavy effort as a shared route on the configured tracker."
disable-model-invocation: true
---

# Wayfinder

Run this manual workflow only inside an active Grillmester session. If another
outer role is active, stop and ask the user to switch with
`copilot --agent grillmester --model claude-opus-5 --context default`; do not
perform the workflow in that role.

Wayfinder is a situational entry point for greenfield or very large work, not
the main loop for ordinary issues. It maps a route to a destination; it does not
implement the destination.

## Contract

- Use `/grilling` for destination and decisions; research facts, but the user
  owns choices. Ask one question at a time.
- A map is one GitHub issue with child issues as decision tickets. The map is an
  index: the ticket holds detail, the map only a linked one-line conclusion.
- Refer to tickets by linked title, not number alone. Complete at most one ticket
  per session. Research also handles one named ticket and one read-only worker
  at a time.
- Read `docs/agents/issue-tracker.md` and `triage-labels.md` before tracker use.
  If mappings for `wayfinder:map` and type labels
  `wayfinder:research|prototype|grilling|task` do not exist, stop with
  `NEEDS_DECISION: Wayfinder label mappings are missing`. Do not create labels
  or a comment that pretends to be a label.

## Map and tickets

```markdown
## Destination
<what is true once the route is found>

## Notes
<constraints and relevant skills>

## Decisions so far
- [<closed ticket title>](link) — <one-line answer>

## Not specified yet
<in-scope uncertainty that cannot yet become a precise question>

## Out of scope
<deliberately bounded work>
```

Each child ticket has one precise question and one type: Research, Prototype,
Grilling, or Task. Use GitHub native sub-issues and dependencies. A ticket is
frontier when open, unblocked, and unassigned. Assignment is claim.

Research is AFK and establishes sourced facts only. Prototype and Grilling are
HITL: they resolve only through a live user response, and the agent never
supplies that response. A Task is AFK only when it merely unblocks a decision
and needs no user authority; otherwise give the user a precise checklist.

## Draw the map

1. Grill the destination and map the breadth. If the route is clear in one
   session, recommend a normal brief or specification instead.
2. Present the map, questions, types, blockers, frontier, and uncertainty.
3. Wait for explicit human confirmation of both map and tracker writing.
4. Verify label and operation prerequisites. Create the map, then tickets, then
   dependency edges in a new pass.
5. Ask separately for explicit confirmation to dispatch one named Research
   ticket. That confirmation authorizes one read-only `/bounded-research`
   worker only; it does not authorize a tracker write. The worker returns a
   cited note.
   Present the note and proposed one-line conclusion. Ask for explicit
   confirmation that authorizes exactly three writes for that note: post the
   resolution comment, close its ticket, and add its linked conclusion to the
   map. Confirmation never carries to another note or worker. Do not create
   shared mutable task state. Stop after dispatch or after the individually
   confirmed recording.

## Work through the map

Load the map, choose the named or first frontier, and claim only after confirmed
writing. Resolve only that ticket with the right skill. Post a resolution
comment in the language required by the repository policy, close the ticket,
and add its linked conclusion to the map. New uncertainty that becomes precise
becomes a ticket after new confirmation; otherwise it remains uncertainty.
When a resolved choice deserves an ADR, glossary entry, or `docs/context.md`
update, recommend Grill with docs and wait for the user to select it. That
workflow's separate write confirmation is required before any documentation
change.

When the route is clear, stop. Summarize the destination, resolved decisions,
remaining uncertainty, and available next steps. Recommend one next step with a
reason, but do not create a specification, implementation ticket, code change,
tracker write, or documentation change unless the user explicitly selects and
authorizes it.
