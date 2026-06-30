# ADR-mal — NAV-utvidet

Den NAV-utvidede malen for Architecture Decision Records. Grunnfeltene (Status / Kontekst / Beslutning / Konsekvenser / Alternativer vurdert) eies av `/domain-modeling` (ADR-FORMAT.md); denne malen legger NAV-spesifikke seksjoner (sikkerhet/personvern, plattform, migrasjon) på toppen. Fyll inn avsnittene som er relevante, slett resten. Hold ADR-en kort og fokusert — én beslutning per ADR.

## Filnavn

`docs/adr/NNNN-<kort-tittel>.md`  (samme `NNNN-`-nummerering som `/grill-with-docs` og `/domain-modeling`)

## Mal

```markdown
# NNNN: {Tittel}

**Dato:** YYYY-MM-DD
**Status:** Foreslått | Godkjent | Forkastet | Erstattet av NNNN
**Beslutningstakere:** {team eller personer}

## Kontekst

- Hva er problemet eller muligheten?
- Hvorfor må vi ta en beslutning nå?
- Hvilke begrensninger gjelder (regulatorisk, plattform, team-kapasitet, eksisterende ADR-er i `docs/adr/`)?

## Beslutning

Vi har besluttet å {konkret valg}.

## Alternativer vurdert

### Alternativ A: {navn} (valgt)

**Beskrivelse:** ...

**Fordeler:**
- ...

**Ulemper:**
- ...

### Alternativ B: {navn}

**Beskrivelse:** ...

**Fordeler:**
- ...

**Ulemper:**
- ...

### Alternativ C: Gjøre ingenting

**Beskrivelse:** Beholde nåværende løsning.

**Fordeler:**
- Ingen endringskostnad.

**Ulemper:**
- {konsekvens av å ikke gjøre noe}

## NAV-spesifikke vurderinger

### Sikkerhet og personvern
- **Dataklassifisering:** Åpen / Intern / Fortrolig / Strengt fortrolig
- **Auth-mekanisme:** Azure AD / TokenX / Maskinporten (se `/auth-overview`)
- **PII-håndtering:** {hvordan fnr og særlige kategorier beskyttes i logg, lagring, transport — bruk callId/aktørreferanse i logg, ikke rå PII}
- **Tilgangsstyring:** {accessPolicy-strategi — `inbound`/`outbound` i NAIS-manifestet}
- **Personvern:** {DPIA-vurdert? kontaktet personvernombud? behandlingsgrunnlag?}

### Plattform (NAIS/GCP)
- **Infrastrukturkrav:** {Cloud SQL Postgres / Kafka (Aiven via Kafkarator) / Bucket / ...}
- **Ressursbehov:** {CPU/minne-requests, replicas — husk: ikke sett `resources.limits.cpu` på NAIS}
- **Observerbarhet:** {Prometheus-metrikker, strukturert JSON-logg uten PII, OpenTelemetry-tracing, alerts}
- **CI/CD-endringer:** {nye GitHub Actions-workflows, deploy-strategi, feature toggles}

### Team og organisasjon
- **Berørte team:** {konsumenter, produsenter, plattformteam}
- **Architecture Advice:** {hvem er rådspurt, når, hva var tilbakemeldingen}
- **Migrasjonsstrategi:** {nåtilstand → måltilstand}
- **Tilbakerulling:** {rollback-plan uten datatap}
- **Tidsramme:** {når skal dette være på plass}

## Migrasjon (ved endring av eksisterende system)
- **Bakoverkompatibilitet:** {kan gammel kode kjøre med nytt skjema / ny event-kontrakt?}
- **Utrullingsstrategi:** big bang / gradvis / parallell drift
- **Feature toggle:** {toggle-navn og strategi}
- **Rollback-trigger:** {hva utløser rollback}
- **Exit criteria:** {når er migreringen ferdig}
- **Dekommisjonering:** {plan for gammel løsning}
- **Migrasjons-observerbarhet:** {gammel vs ny path, avviksteller, rekonsiliering}

## Konsekvenser

### Positive
- ...

### Negative
- ...

### Risiko

| Risiko | Sannsynlighet | Konsekvens | Mitigering |
|--------|--------------|------------|-----------|
| ... | Lav/Middels/Høy | ... | ... |

## Aksjonspunkter

- [ ] {oppgave} — {eier} — {frist}
- [ ] Oppdater NAIS-manifest (inkl. `accessPolicy`) — se `/nais-manifest`
- [ ] Sett opp observerbarhet (metrikker, logg, alerts)
- [ ] Informer berørte team
- [ ] Bryt arbeidet ned i `.grill/PLAN.md` (evt. `/to-issues`)
- [ ] Definer bevis i `.grill/VERIFICATION.md`
```

## Tips

- Hold ADR-er korte og fokuserte — én beslutning per ADR.
- «Gjøre ingenting» er alltid et alternativ.
- Skriv for fremtidige lesere (og @grillmester i en senere tråd) som ikke kjenner konteksten.
- Bruk domenets ord fra `docs/GLOSSARY.md`, ikke ad-hoc-navn.
- Oppdater status når beslutningen er tatt; «Erstattet av NNNN» når en beslutning revideres.
- Ikke legg PII eller hemmeligheter i selve ADR-en — referer til riktig kilde i stedet.
