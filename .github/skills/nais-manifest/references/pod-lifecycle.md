---
applyTo: "**/nais/**,**/.nais/**,**/nais*.yaml,**/nais*.yml"
description: "Hvordan NAIS håndterer pod-shutdown (preStop, SIGTERM, lastbalansering) og hvilke shutdown-mønstre som er riktige i et Ktor-backend. Les ved spørsmål om graceful shutdown, terminationGracePeriodSeconds, avbrutte requests eller readiness under nedstenging."
---

# Pod-lifecycle og graceful shutdown i NAIS

- NAIS injiserer en `preStop`-hook med `sleep 5` før `SIGTERM` sendes til applikasjonen.
- I denne perioden slutter lastbalansereren å rute ny trafikk til poden.
- Readiness-probes er **ikke** involvert i shutdown-flyten i NAIS.
- Å sette readiness=false manuelt i app-kode har derfor ingen effekt og er et anti-mønster.
- Applikasjonen trenger kun å: (a) drenere in-flight requests og (b) avslutte rent.
- I Ktor: bruk `ApplicationStopping`/`ApplicationStopped`-hendelsene (eller `embeddedServer(...).stop(gracePeriod, timeout)`) til å lukke connection pool (Hikari), Kafka-consumer/-producer og andre ressurser kontrollert. Ikke lag egne readiness-toggles.
- Ikke senk `terminationGracePeriodSeconds` under default `30` sekunder.
- Lavere grace-periode reduserer tiden appen har til drenering og kontrollert avslutning.
- Kort grace-periode øker risiko for avbrutte kall og uferdig opprydding.
