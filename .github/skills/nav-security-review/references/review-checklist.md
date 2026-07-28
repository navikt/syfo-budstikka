# Security review checklist — Nav focus

- [ ] PII classification is clear for all data (strictly confidential/confidential/internal/open) and noted in `docs/context.md`.
- [ ] No fnr, names, health data, or sensitive benefit data appear in standard logs.
- [ ] CEF audit logging covers Nav-employee display of personal data.
- [ ] `accessPolicy.inbound` is explicit and mirrors auth-code validation.
- [ ] `accessPolicy.outbound` is limited to necessary services/hosts with correct cluster/namespace.
- [ ] Secrets come only from Nais Console; no hardcoded values or local production secrets.
- [ ] `Nav-Call-Id` is propagated (CallId plugin → MDC) for correlation.
- [ ] Legal basis, retention, and deletion are documented for personal data.
- [ ] Queries are parameterised, input is validated, and access control checks ownership, not only a valid token.
- [ ] `trivy repo .` has no HIGH/CRITICAL findings, `zizmor` is OK, and no secrets are committed.
- [ ] The security champion is considered for new data categories, integrations, or auth changes.
- [ ] DPIA need is assessed before new personal-data processing; read `nav-threat-model.md`.
