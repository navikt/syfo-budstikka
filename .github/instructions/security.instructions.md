---
description: "Always-on security boundary: protect secrets and personal data; use existing controls and request review for uncertainty."
applyTo: "**"
---

# Security boundaries

This service may process sickness-absence data. Never commit, hard-code, log,
or return secrets, tokens, bearer headers, national identity numbers, names, or
health data. Use existing authentication, secret, and access patterns; do not
invent parallel controls.

Treat auth, personal data, external traffic, data movement, and new data
categories as security-relevant. Keep Nais access least-privilege and obtain
fresh verification. Use the path-scoped security guidance for implementation
detail and `/nav-security-review` when uncertainty remains.
