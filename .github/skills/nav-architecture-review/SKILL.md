---
name: nav-architecture-review
description: "Use for NAV-specific architecture review of a new service, cross-team integration, storage or event seam, authentication, accessPolicy, personal data, platform migration, or deviation from NAV standards. Create an ADR only when the decision passes the shared three-part ADR gate."
---

# NAV Architecture Review — ADR + 3-perspektiv

Skriv Architecture Decision Records (ADR) og gjør tyngre arkitektur-review for dette repoet. Skillen dekker det som er NAV- og backend-spesifikt: NAIS/GCP-plattformen, TokenX/Azure AD/Maskinporten, `accessPolicy`, Datatilsynet/DPIA og NAVs arkitekturprinsipper (Team First, Architecture Advice Process, foretrekk plattform-kapabiliteter framfor egenbygd).

**Role:** review NAV- and backend-specific implications. Formalise a decision as
an extended NAV ADR only when it passes `/domain-modeling`'s ADR gate. Find
candidates with `/improve-codebase-architecture`, interrogate the choice with
`/grill-with-docs`, and design the interface with `/codebase-design`.
`/domain-modeling` owns the minimal ADR format; this skill adds NAV-specific
analysis when that specialised review is needed.

Generisk «hva er en ADR» eller generiske OWASP-lister er ikke gjengitt her — bruk det fra ditt eget repertoar.

## When to run the review

A NAV architecture review and an ADR are separate outcomes. The signals below
justify the review; they do not automatically justify an ADR. Create an ADR
only when all three are true:

1. **Hard to reverse** — changing the decision later has meaningful cost.
2. **Surprising without context** — a future reader would reasonably ask why
   this path was chosen.
3. **The result of a real trade-off** — genuine alternatives were considered
   and one was selected for specific reasons.

If any test is missing, complete the relevant NAV review without creating an
ADR.

Typical review signals:

- A new service, cross-team integration, storage layer, or Kafka/event
  contract.
- An `accessPolicy` change owned by another team, or a new authentication
  mechanism such as TokenX, Azure AD, or Maskinporten.
- Processing a new category of personal data, potentially requiring a DPIA.
- A platform migration or technical-debt change that moves a seam.
- A deviation from NAV standards or introduction of new technology.

For a choice that does not pass the ADR gate, keep task-scoped reasoning in the
issue or active plan. Put durable maintained detail in the relevant topic
document, and update `docs/context.md` only when repository orientation or
overall status changes.

## Stacken er gitt — forslag skal matche den

Dette repoet er Kotlin + Ktor 3.x på Netty, Java 25, Gradle, NAIS, med Postgres/Flyway og Kafka der det trengs. Et ADR-alternativ skal holde seg innenfor dette stacket med mindre selve beslutningen er å bytte stack — ikke dra inn Spring, et nytt språk eller en ny kjøretid uten at det er det eksplisitte valget under vurdering.

## 3-perspektiv-review

Evaluer endringen fra tre perspektiver før ADR-en konkluderer. Skriv én til tre linjer per perspektiv — bekymring, risiko, anbefaling.

1. **Arkitektur** — passer i NAVs overordnede arkitektur, respekterer team-autonomi, gjenbruker plattform-kapabiliteter, unngår accidental complexity. Bruk slettetesten på nye lag (jf. `/improve-codebase-architecture`): konsentrerer modulen kompleksitet, eller flytter den bare?
2. **Sikkerhet** — dataklassifisering, auth-mekanisme, `accessPolicy` (inngang/utgang), PII-beskyttelse i logg/lagring/transport, behov for DPIA.
3. **Plattform** — NAIS-manifest-endringer, ressursbehov, observerbarhet (Prometheus/Loki/Tempo), CI/CD, avhengigheter til on-prem eller legacy.

Full sjekkliste med NAV-spesifikke spørsmål per perspektiv ligger i [references/perspektiv-sjekklister.md](references/perspektiv-sjekklister.md).

Ved endring av eksisterende system: ta også med migrasjon (bakoverkompatibilitet, rollback-plan, feature toggle, exit criteria, dekommisjonering).

## Alternativer og Architecture Advice Process

Dokumenter minst to alternativer pluss «gjøre ingenting». NAVs Architecture Advice Process er rådgivende, ikke godkjenningsbasert: teamet søker råd fra berørte parter, men eier beslutningen selv. Identifiser berørte team (konsumenter/produsenter av data eller events) tidlig og del utkast-ADR med dem før beslutningen fattes.

## ADR-format og lagring

Enter this section only after the decision passes all three ADR tests.
Use the minimal ADR base from `/domain-modeling` and save it as
`docs/adr/NNNN-<short-title>.md`, incrementing the highest existing number.
Because this specialised NAV review is active, use
[references/adr-template.md](references/adr-template.md) to add the structured
three-perspective, security, platform, migration, and alternatives analysis
that this branch requires.

Korte ADR-er er best — én beslutning per ADR. Oppdater status når beslutningen er tatt; bruk «Erstattet av NNNN-…» når en beslutning revideres.

## Kobling til faseløkka

- **Input:** findings from the grill/design phase and
  `/improve-codebase-architecture` feed this review. Follow the narrow load
  order in `docs/agents/domain.md`: load the glossary only when terminology is
  in play, the relevant topic document or ADR when needed, and
  `docs/context.md` only for orientation or overall status.
- **Output:** when the three-part gate passes, write the focused decision to
  `docs/adr/`. Put resolved vocabulary in `docs/glossary.md`, durable detail in
  the relevant topic document, and task-scoped choices in the issue or active
  plan. Update `docs/context.md` only when orientation or overall status
  changes. When the Grillmester phase loop is active, action items may live in
  `.grill/PLAN.md` (or `/to-issues`) and verification evidence in
  `.grill/VERIFICATION.md`.

## Relaterte skills

- `/nais-manifest` — manifest-struktur, `resources`, `accessPolicy`, Cloud SQL, Kafka pool.
- `/auth-overview` — Azure AD, TokenX, Maskinporten, Texas/Oasis.
- `/kotlin-ktor` — konkret Ktor-implementasjon av beslutningen (routes, plugins, auth, DI).
- `/postgresql-review` og `/flyway-migration` — lagrings- og skjemavalg.
- `/kafka-topic` — event-kontrakter og topic-oppsett.
- `/improve-codebase-architecture` — oppdager fordypningsmuligheter som ofte utløser en ADR.
- `/grill-with-docs` — grilling som skjerper alternativene før ADR-en konkluderer.
- `/diagnosing-bugs` — drift-/feildiagnose, ikke designbeslutning.

## Grenser

### Alltid

- Inkluder minst to alternativer (pluss «gjøre ingenting»).
- Vurder alle tre perspektiver — arkitektur, sikkerhet, plattform.
- Dokumenter NAV-spesifikke vurderinger: auth, dataklassifisering, `accessPolicy`, NAIS-endringer.
- Avslutt med konkrete aksjonspunkter (eier + frist).

### Spør først

- ADR som påvirker andre teams tjenester eller kontrakter.
- Beslutninger som avviker fra NAV-standardmønstre (NAIS-plattform, Kafkarator, Cloud SQL).
- Introduksjon av ny teknologi, nytt språk eller ny plattform-komponent.
- Behandling av nye kategorier personopplysninger — vurder DPIA og kontakt personvernombud.

### Aldri

- Fatt arkitekturbeslutning uten å vurdere sikkerhet og personvern.
- Ignorer plattform-konsekvenser (ressurser, observerbarhet, `accessPolicy`).
- Hopp over alternativer — det finnes alltid minst to valg.
- Skriv fødselsnummer, andre PII eller hemmeligheter i selve ADR-en — referer til riktig kilde i stedet.
