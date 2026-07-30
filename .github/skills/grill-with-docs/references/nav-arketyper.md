# NAV-arketyper og domene-spørsmål

Structured discovery prompts for NAV projects. Choose an application shape,
ask the domain-specific questions, and route findings through
`docs/agents/domain.md`. This repository is a **Ktor backend**, so favour the
backend-oriented branches while retaining the full menu for BFF/full-stack
surfaces.

## Steg 1: Arketype

Still dette først:

> Hva slags ting bygger du?
> - **Backend API** (Kotlin/Ktor) ← typisk her
> - **Hendelsekonsument** (Kafka / Rapids & Rivers)
> - **Batchjobb** (Naisjob)
> - **Fullstack / BFF** (backend + frontend-flate)

## Steg 2: Domene-spesifikke spørsmål

Still fra **alle fire domener**. Tilpass rekkefølge etter arketype.

### Personvern og data (still først — glemmes oftest)

| # | Spørsmål | Hvorfor |
|---|----------|---------|
| D1 | Behandler tjenesten personopplysninger? Hvilke kategorier? | Bestemmer dataklassifisering og lagringsregler |
| D2 | Hvem har tilgang — innbygger, saksbehandler, system? | Bestemmer auth og tilgangskontroll |
| D3 | Hva er formålet (hjemmel)? | Nødvendig for GDPR-vurdering |
| D4 | Hvor lenge lagres data? Sletteregler? | Påvirker schema og retensjon |
| D5 | Deles data med andre tjenester? Hvilke? | Påvirker API-design og accessPolicy |
| D6 | Trengs audit-logging av hvem som så/endret data? | Påkrevd for sensitive personopplysninger |

If the classification or affected data categories are unclear or changing,
use [data-classification.md](./data-classification.md) for NAV-specific prompts
and consequences.

### Plattform og autentisering

| # | Spørsmål | Hvorfor |
|---|----------|---------|
| P1 | Hvem initierer forespørsler — bruker, tjeneste, batch, ekstern? | Bestemmer auth-mekanisme (TokenX vs Azure AD) |
| P2 | Hvilke andre tjenester kaller dere? Hvilket cluster? | Outbound accessPolicy + token exchange |
| P3 | Eksponert eksternt eller bare internt? | Ingress og nettverkspolicy |
| P4 | Hva skjer når en avhengighet er nede? | Retry-strategi / circuit breaker |
| P5 | Trengs asynkron kommunikasjon (hendelser)? | Kafka-oppsett eller ikke |
| P6 | Finnes eksisterende tjenester å gjenbruke? | Unngå duplikering |

### Observerbarhet og drift

| # | Spørsmål | Hvorfor |
|---|----------|---------|
| O1 | Hvilke forretningsmetrikker viser at tjenesten fungerer? | Definerer Prometheus-metrikker |
| O2 | Hva skal trigge varsling? | Alerting-regler |
| O3 | Hvordan vet dere at en deploy gikk bra? | Smoke-test-strategi |
| O4 | Forventet trafikkmønster (jevnt, kontortid, sesong)? | Skalering og ressurser |

### Team og prosess

| # | Spørsmål | Hvorfor |
|---|----------|---------|
| T1 | Nytt prosjekt eller utvidelse/modernisering? | Scaffold vs. migrasjonsstrategi |
| T2 | Avhenger dere av andre team? Hvilke? | Koordineringsbehov |
| T3 | Regulatorisk/politisk deadline? | Scope og prioritering |

### Modernisering og migrasjon (still hvis T1 avdekker det)

| # | Spørsmål | Hvorfor |
|---|----------|---------|
| M1 | Hva finnes i dag — teknologi, arkitektur, datamodell? | Kartlegger utgangspunktet |
| M2 | Hva er feil med dagens løsning? | Motivasjon og prioritet |
| M3 | Må gammel og ny kjøre parallelt? Hvor lenge? | Big bang vs. gradvis |
| M4 | Hvem konsumerer API-er/hendelser som endres? | Bakoverkompatibilitet |
| M5 | Rollback-plan hvis migreringen feiler? | Må defineres FØR start |
| M6 | Exit criteria — når er migreringen ferdig? | Når kan gammelt dekommisjoneres |
| M7 | Data som må migreres? Hvor mye? | Migreringspipeline / nedetid |
| M8 | Karakteriseringstester som låser dagens adferd? | Trygg refaktorering |

## Steg 3: Endringskonsekvensanalyse (brownfield)

| # | Spørsmål | Formål |
|---|----------|--------|
| K1 | Hvem kaller API-ene dine i dag? (`accessPolicy.inbound`) | Direkte konsumenter |
| K2 | Hvem konsumerer Kafka-hendelsene dine? | Downstream-avhengigheter |
| K3 | Har andre tjenester tilgang til databasen din? | Delt-database-risiko |
| K5 | Påvirker endringen data brukt til utbetaling? | Høy risiko — ekstra review |
| K7 | Krever endringen koordinert deploy med andre team? | Logistisk risiko |
| K8 | Endres API-kontrakter (eksplisitte eller implisitte)? | Kontraktsbrudd |
| K9 | Testtilstand for koden som endres? | Endringssikkerhet |

## Step 4: Route documentation through the repository seam

Follow `docs/agents/domain.md`: resolved vocabulary goes to
`docs/glossary.md`, maintained detail to the relevant topic document, and task
scope to the issue or plan. A hard-to-reverse, surprising, real trade-off gets
one focused `docs/adr/NNNN-*.md`. Update `docs/context.md` only when repository
orientation or overall status actually changes.

## Prinsipper
- Still personvernspørsmål først — de glemmes oftest.
- Verifiser auth-mekanisme mot caller-type. Anta aldri at auth ikke trengs.
- Dokumenter ikke-mål eksplisitt. Identifiser minst én risiko.
- Aldri foreslå at PII kan logges.
