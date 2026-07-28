---
description: "Provides a complete syfo-budstikka Application template with port, Prometheus, liveness/readiness, resources, and inbound/outbound accessPolicy rules. Read when writing or extending an Application manifest."
---

# Application — template and accessPolicy example

## Application template

```yaml
apiVersion: nais.io/v1alpha1
kind: Application
metadata:
  name: syfo-budstikka
  namespace: team-esyfo          # Read from the existing manifest
  labels:
    team: team-esyfo
spec:
  image: {{ image }}
  port: 8080                     # Ktor/Netty listens here

  prometheus:
    enabled: true
    path: /internal/metrics
  liveness:
    path: /internal/health/is_alive
    initialDelay: 5
  readiness:
    path: /internal/health/is_ready
    initialDelay: 5

  resources:
    requests:
      cpu: 50m
      memory: 256Mi
    limits:
      memory: 512Mi              # Never set CPU limits; see SKILL.md
```

## accessPolicy example

```yaml
accessPolicy:
  inbound:
    rules:
      - application: calling-app
        namespace: calling-team
      - application: another-service
        namespace: other-team
        cluster: prod-gcp
  outbound:
    rules:
      - application: pdl-api
        namespace: pdl
      - application: syfo-nedstroms
        namespace: team-esyfo
    external:
      - host: api.ekstern-tjeneste.no
```
