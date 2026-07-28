# Three-perspective checklists for the Ktor backend

These checklists cover Nav- and backend-specific concerns. Apply general
architecture and security expertise separately.

## Architecture

- Respect team autonomy: seek Architecture Advice, but make the team’s decision.
- Identify and consult affected data/event consumers and producers.
- Reuse platform capabilities: Texas/Oasis for auth, NAIS or Google Secret Manager
  for secrets, Aiven through Kafkarator for Kafka, Cloud SQL through NAIS for
  database, and Prometheus/Loki/Tempo for metrics, logs, and tracing.
- Prefer Kafka/events for asynchronous cross-team communication and REST only
  where appropriate; use API contracts rather than shared databases.
- Is responsibility bounded and essential rather than accidental complexity?
  Apply `/improve-codebase-architecture`’s deletion test to new layers.
- Plan away on-prem or legacy dependencies, document Nav-standard deviations,
  and assess DORA impact on deployment, lead time, failure rate, and recovery.

## Security

- Classify data: Open / Internal / Confidential / Strictly confidential.
- Select auth correctly: TokenX for end-user OBO, Azure AD for Nav employees,
  TokenX or Azure AD client credentials for internal service-to-service, and
  Maskinporten for external organisations.
- Limit `accessPolicy.inbound.rules` to necessary callers and declare every
  outbound call. Avoid wildcards and align Ktor authentication with the policy.
- Never log national ID, name, address, or case content; use callId or actor
  reference. Protect data with TLS and encryption at rest. Never log raw Kafka PII.
- Assess DPIA, privacy officer consultation, legal basis, Datatilsynet notice,
  secret storage, and searchable audit trails.

## Platform

- Set realistic NAIS requests, no CPU limit, a memory limit, and suitable replicas.
  Never manually toggle readiness during shutdown.
- For Cloud SQL, assess instance type, disk, backup, flags, and append-only
  Flyway migration. For Kafka, use Kafkarator, select pool/retention, and make
  consumers idempotent and replay-safe.
- Establish metrics, structured JSON logs without PII, tracing where available,
  meaningful alerts and a runbook from day one.
- Keep CI/CD deterministic with `./gradlew test` and `./gradlew build`; choose a
  rollout and rollback strategy. Estimate resource, egress, storage, and Cloud SQL cost.
- Define operating ownership and on-call responsibility before production release.
