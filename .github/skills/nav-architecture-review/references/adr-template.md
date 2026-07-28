# ADR template — Nav extension

This is the Nav-extended Architecture Decision Record template. `/domain-modeling`
owns the base fields (Status / Context / Decision / Consequences / Alternatives
considered) in `../../domain-modeling/references/adr-format.md`; this template
adds security/privacy, platform, and migration sections. Fill only relevant
sections and delete the rest. Keep one short, focused decision per ADR.

## Filename

`docs/adr/NNNN-<short-title>.md` (the same `NNNN-` sequence used by
`/grill-with-docs` and `/domain-modeling`)

## Template

```markdown
# NNNN: {Title}

**Date:** YYYY-MM-DD
**Status:** Proposed | Accepted | Rejected | Superseded by NNNN
**Decision makers:** {team or people}

## Context

- What is the problem or opportunity?
- Why must a decision be made now?
- Which constraints apply: regulation, platform, team capacity, existing ADRs?

## Decision

We decide to {concrete choice}.

## Alternatives considered

### Alternative A: {name} (chosen)
**Description:** ...
**Advantages:**
- ...
**Disadvantages:**
- ...

### Alternative B: {name}
**Description:** ...
**Advantages:**
- ...
**Disadvantages:**
- ...

### Alternative C: Do nothing
**Description:** Keep the current solution.
**Advantages:**
- No change cost.
**Disadvantages:**
- {consequence of doing nothing}

## Nav-specific considerations

### Security and privacy
- **Data classification:** Open / Internal / Confidential / Strictly confidential
- **Auth mechanism:** Azure AD / TokenX / Maskinporten (see `/auth-overview`)
- **PII handling:** {how fnr and special-category data are protected in logs, storage, and transport; use callId/actor reference in logs, never raw PII}
- **Access control:** {accessPolicy strategy: NAIS manifest `inbound`/`outbound`}
- **Privacy:** {DPIA assessed? privacy officer consulted? legal basis?}

### Platform (NAIS/GCP)
- **Infrastructure:** {Cloud SQL Postgres / Kafka through Kafkarator / Bucket / ...}
- **Resources:** {CPU/memory requests, replicas; never set `resources.limits.cpu` on NAIS}
- **Observability:** {Prometheus metrics, structured JSON logging without PII, OpenTelemetry tracing, alerts}
- **CI/CD:** {GitHub Actions changes, deployment strategy, feature toggles}

### Team and organisation
- **Affected teams:** {consumers, producers, platform team}
- **Architecture Advice:** {who was consulted, when, and feedback}
- **Migration strategy:** {current state → target state}
- **Rollback:** {rollback plan without data loss}
- **Timeframe:** {when it must be in place}

## Migration (when changing an existing system)
- **Backward compatibility:** {can old code run with the new schema/event contract?}
- **Rollout strategy:** big bang / gradual / parallel operation
- **Feature toggle:** {toggle name and strategy}
- **Rollback trigger:** {what triggers rollback}
- **Exit criteria:** {when migration is complete}
- **Decommissioning:** {plan for old solution}
- **Migration observability:** {old versus new path, discrepancy counter, reconciliation}

## Consequences
### Positive
- ...
### Negative
- ...
### Risks
| Risk | Likelihood | Consequence | Mitigation |
|---|---|---|---|
| ... | Low/Medium/High | ... | ... |

## Action items
- [ ] {task} — {owner} — {deadline}
- [ ] Update NAIS manifest, including `accessPolicy`; see `/nais-manifest`
- [ ] Add observability: metrics, logging, alerts
- [ ] Inform affected teams
- [ ] Break work into a task-scoped brief; optionally recommend explicit issue-management
- [ ] Define proof in the brief’s `verification`
```

## Guidance

- Keep ADRs short and focused: one decision per ADR.
- “Do nothing” is always an alternative.
- Write for future readers and Grillmester in a later task that lacks current context.
- Use domain terms from `docs/glossary.md`, not ad-hoc names.
- Update status when decided; use “Superseded by NNNN” when revising a decision.
- Never put PII or secrets in the ADR; refer to the correct source instead.
