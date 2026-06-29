---
name: nav-architecture-review
description: "Bruk når en tyngre arkitekturbeslutning bør festes som ADR: ny tjeneste/integrasjon mot annet team, nytt lagringslag eller event-kontrakt, ny auth-mekanisme, accessPolicy mot andre team, nye personopplysninger (DPIA), eller avvik fra NAV-standard. Triggere: 'skriv en ADR', 'arkitektur-review', 'bør vi X eller Y'."
---

# NAV Architecture Review — ADR + 3-perspektiv

Skriv Architecture Decision Records (ADR) og gjør tyngre arkitektur-review for dette Ktor-backendet (`no.nav.syfo`). Skillen dekker det som er NAV- og backend-spesifikt: NAIS/GCP-plattformen, TokenX/Azure AD/Maskinporten, `accessPolicy`, Datatilsynet/DPIA og NAVs arkitekturprinsipper (Team First, Architecture Advice Process, foretrekk plattform-kapabiliteter framfor egenbygd).

Generisk «hva er en ADR» eller generiske OWASP-lister er ikke gjengitt her — bruk det fra ditt eget repertoar.

## Når dette utløses

Dette er en arkitekturbeslutning i @grillmester sin grill-/design-fase (fase 1–2). Tunge endringer fortjener en ADR; lettere valg gjør ikke det.

Typiske signaler:

- Ny tjeneste, ny integrasjon mot annet team, nytt lagringslag eller ny Kafka-/event-kontrakt.
- Endring som krever `accessPolicy`-oppdatering hos andre team, eller ny auth-mekanisme (TokenX/Azure AD/Maskinporten).
- Behandling av nye personopplysninger (mulig DPIA / melding til Datatilsynet).
- Plattform-migrering eller opprydding i teknisk gjeld som flytter en søm.
- Avvik fra NAV-standardmønstre eller introduksjon av ny teknologi i stacken.

Lettere valg (biblioteksvalg innenfor eksisterende stack, intern refaktor uten ny søm) trenger ikke formell ADR — bare et notat i `docs/CONTEXT.md`.

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

Bruk det kanoniske ADR-formatet fra `/grill-with-docs` og `/domain-modeling` (Status / Kontekst / Beslutning / Konsekvenser / Alternativer vurdert), og lagre som `docs/adr/NNNN-<kort-tittel>.md` med samme `NNNN-`-nummerering (skann høyeste nummer i `docs/adr/` og legg +1). Da finner og respekterer `/grill-with-docs`, `/domain-modeling` og `/improve-codebase-architecture` den. De NAV-spesifikke vurderingene (3-perspektiv, auth, dataklassifisering, `accessPolicy`) legges som underseksjoner i samme ADR — se [references/adr-template.md](references/adr-template.md) for en utvidet NAV-ADR.

Korte ADR-er er best — én beslutning per ADR. Oppdater status når beslutningen er tatt; bruk «Erstattet av NNNN-…» når en beslutning revideres.

## Kobling til faseløkka

- **Input:** funn fra grill-/design-fasen og fra `/improve-codebase-architecture` mater hit. Les `docs/CONTEXT.md` og `docs/GLOSSARY.md` så ADR-en bruker domenets egne ord, ikke ad-hoc-navn.
- **Output:** skriv den valgte tilnærmingen til `docs/CONTEXT.md` og selve beslutningen til `docs/adr/`. Konkrete aksjonspunkter brytes ned i `.grill/PLAN.md` (evt. via `/to-issues`), og hva som beviser at valget holder, fanges i `.grill/VERIFICATION.md`.

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
