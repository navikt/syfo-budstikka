---
name: security-review
description: "Sikkerhetsgjennomgang av NAV Kotlin/Ktor-tjenester: PII/FNR/helseopplysninger i logger, secrets, CEF-auditlogg, accessPolicy i NAIS-manifestet, JWT-validering (TokenX/Azure AD), eksterne integrasjoner, DPIA og eskalering til sikkerhetschampion. Brukes før commit/push/PR med sikkerhetsrelevans, eller når noen sier /security-review."
---

# Sikkerhetsgjennomgang — NAV-kontekst

NAV-spesifikk sikkerhetssjekk før commit, push og PR i dette repoet. Generiske OWASP-mønstre (SQLi, XSS, CSRF, injection) forutsettes kjent — dette dokumentet fokuserer på NAV-konteksten: PII-klassifisering, accessPolicy som sikkerhetsmekanisme, og eskalering til sikkerhetschampion. For JWT-validering, claims og auth-oppsett i koden, se `/auth-overview`.

## Flytkobling

Denne skillen brukes typisk i **verifiser**-fasen av @grillmester sin faseløkke, og før PR. Når gjennomgangen avdekker varig verdi:

- En kandidat som passerer ADR-gaten → anbefal dokumentert løp og vent på
  brukerens valg før `/domain-modeling` skriver.
- Returner reviewfunn og deterministiske verktøybevis (trivy/zizmor-output,
  exit-koder) til den aktive oppgaven. Skriv dem bare til en oppgavelokal
  `.grill/`-fil når den kallende arbeidsflyten eksplisitt har valgt det.
- Vedlikeholdte rammer for datahåndtering som følger av en godkjent endring →
  relevant topic-dokument. Nye domenebegreper er kandidater for dokumentert løp;
  vent på brukerens valg før glossaret oppdateres. Oppdater `docs/context.md`
  bare ved endret orientering eller overordnet status.

## PII-klassifisering i NAV

NAV behandler personopplysninger med fire beskyttelsesnivåer. Feil klassifisering er den vanligste rotårsaken til alvorlige avvik.

| Nivå | Typiske data | Behandling |
|------|--------------|------------|
| **Strengt fortrolig** | Helseopplysninger, diagnoser, sykmeldinger, voldsutsatte/kode 6, barnevernsdata | Kryptering i ro og transit, streng tilgangsstyring, vurder dokumenterte audit- og DPIA-krav |
| **Fortrolig** | Fødselsnummer (fnr), D-nummer, kode 7, sensitive ytelsesdata | Aldri i standardlogger, følg dokumentert auditkrav, tilgangsstyring per sak/bruker |
| **Intern** | Navn, adresse, telefon, e-post, ikke-sensitiv ytelsesstatus | Dataminimering, tilgang per tjenstlig behov, retention dokumentert |
| **Åpen** | Offentlig statistikk, anonymiserte aggregater | Normal tilgang; verifiser at anonymiseringen tåler koblingsangrep |

Helseopplysninger og opplysninger om sykefravær kan være særlige kategorier av
personopplysninger. Fastsett klassifiseringen fra repoets domenegrunnlag og
gjeldende policy, ikke fra tjeneste- eller katalognavnet alene.

**Placeholder i kode og dokumentasjon**: Bruk aldri ekte fnr. Bruk en tydelig
navngitt syntetisk testident fra test-fixturet eller Skatteetatens offisielle
testserie, markert eksplisitt som syntetisk. Se
`references/nav-threat-model.md` for DPIA-prosess og audit-krav.

### PII i logger

Undersøk repoets aktive loggingimplementasjon først. Bruk strukturerte felter
når implementasjonen støtter det, og aldri PII i meldingsteksten:

```kotlin
// OK — strukturert teknisk ID, ingen PII (repoets etablerte kv-mønster)
log.info("Processing case", kv("event_id", eventId))

// Aldri — FNR, navn, diagnose eller ytelsesdata i standardlogg
log.info("Processing case for {}", bruker.fnr)
```

Visning av personopplysninger til NAV-ansatte kan kreve **CEF-format** til en
egen auditlogg. Les alltid repoets gjeldende instruksjoner og beslutninger før du
antar audit- eller korrelasjonsmekanismen. Ikke bruk secure logs som en
rømningsvei for PII, legg til CEF uten et faktisk auditbehov, eller presenter en
request-scope-ID som ende-til-ende-korrelasjon over asynkrone grenser uten at
den er persistert. Et nytt behov krever en eksplisitt beslutning. Se
`references/nav-threat-model.md` for generelle CEF- og auditvurderinger.

## accessPolicy som first-line defense

`accessPolicy` i NAIS-manifestet (`nais/` eller repoets tilsvarende sti) er første forsvarslinje — ikke en tilleggsmekanisme. Default deny på NAIS-plattformen betyr at glemt regel = brutt tilgang, ikke åpen tilgang. Men feil regel = eksponert tjeneste.

```yaml
spec:
  accessPolicy:
    inbound:
      rules:
        - application: min-frontend         # eksplisitt navngitt caller
    outbound:
      rules:
        - application: pdl-api
          namespace: pdl
          cluster: prod-gcp
      external:
        - host: api.ekstern-tjeneste.no     # kun når strengt nødvendig
```

**Kritiske vurderinger ved gjennomgang:**

- **Ingen åpen inbound**: `inbound.rules` må være eksplisitt liste. Fravær av rules = ingen tilgang (OK for intern batch/job), men åpne wildcards eller mange generelle rules krever begrunnelse.
- **Inbound vs. auth-kode speiler hverandre**: Hver app i `inbound.rules` skal være validert i auth-koden (`azp`-sjekk mot `AZURE_APP_PRE_AUTHORIZED_APPS` i `authenticate("azureAd")`-grenen). Diff avvik — enten død kode eller manglende nettverksregel.
- **Outbound er et sikkerhetstiltak, ikke bare ruting**: Begrenset outbound = begrenset blast radius hvis appen kompromitteres. Outbound `external` må ha tydelig formål og eier.
- **Cluster/namespace stemmer med miljøet**: `prod-gcp` vs `dev-gcp` — feil cluster i outbound = tjeneste fungerer ikke i prod, men blir ofte oppdaget sent.

## Sikkerhetschampion-rolle og eskalering

Hvert team har en sikkerhetschampion (eller kan eskalere til plattformens sikkerhetsfunksjon). Denne rollen eies av teamet, ikke av `security-review`-skillen.

**Når skillen håndterer det (ingen eskalering):**

- Parameteriserte spørringer, input-validering, standard OWASP-mønstre.
- Auditlogging etter repoets eksplisitte policy når et slikt krav er etablert.
- accessPolicy-oppsett for standard inbound/outbound.
- Trivy/zizmor-funn med kjente fixes.

**Når du eskalerer til sikkerhetschampion (eller `#appsec`):**

- **Ny klasse data**: Første gang teamet behandler helseopplysninger, barnevernsdata eller kode 6/7.
- **DPIA-behov**: Ny behandling med personopplysninger eller vesentlig endring i eksisterende behandling. Se `references/nav-threat-model.md`.
- **Ny integrasjon med eksternt domene**: `outbound.external` mot leverandør/tredjepart.
- **Endring i autentiseringsmekanisme**: Bytte mellom Azure AD/TokenX/ID-porten/Maskinporten, eller ny RBAC-modell.
- **Mistanke om hendelse**: Lekket secret, uautorisert tilgang, avvikende bruksmønster — ikke vent, eskaler umiddelbart.
- **Compliance-vurdering utenfor standardmønster**: Tilsynssaker, Datatilsynet-henvendelser, svar på revisjon.

**Hastegrad:**

- **Akutt (ring/ping umiddelbart)**: Aktiv hendelse, eksponert secret i git-historikk, mistanke om databehandlingsbrudd.
- **Samme dag**: Ny ekstern integrasjon i prod, endret autentiseringsflyt, nye datakategorier.
- **Planlagt (Slack/issue)**: DPIA-forberedelse, arkitekturgjennomgang, trusselmodellering.

Kontaktkanaler (prosess, ikke personer): Teamets interne sikkerhetschampion-kanal; NAVs `#appsec` for generelle spørsmål; `#auditlogging-arcsight` for auditlogg; plattformens sikkerhetsfunksjon for hendelser.

## Automatiserte skanninger

```bash
# Sårbarheter og hemmeligheter i repoet
trivy repo .

# HIGH/CRITICAL CVE-er i container-image
trivy image <image-name> --severity HIGH,CRITICAL

# GitHub Actions workflows
zizmor .github/workflows/

# Hemmeligheter i git-historikk
git log -p --all -S 'password' -- '*.kt' '*.kts' '*.yaml' | head -100
git log -p --all -S 'secret' -- '*.kt' '*.kts' '*.yaml' | head -100
```

Returner bevis (kommando + output + exit-kode) til den kallende arbeidsflyten —
ingen «ser trygt ut»-påstander uten ferskt bevis.

## Hemmeligheter

```kotlin
// OK — fra miljø (NAIS injiserer via Console-secret)
val dbPassword = System.getenv("DB_PASSWORD")
    ?: error("DB_PASSWORD mangler")

// Aldri — hardkodet
val dbPassword = "supersecret123"
```

Secrets opprettes i NAIS Console og injiseres via `envFrom`/`filesFrom`. Sjekk
også at de ikke havner i repoets applikasjonskonfigurasjon,
`gradle.properties` eller version catalog. Kopier aldri prod-secrets lokalt.

## Sjekkliste (NAV-fokus)

- [ ] PII-klassifisering er avklart for all data tjenesten behandler (strengt fortrolig/fortrolig/intern/åpen) og vedlikeholdt i relevant topic-dokument
- [ ] Ingen FNR, navn, helse- eller sensitive ytelsesdata i standardlogger
- [ ] Logging og audit følger repoets dokumenterte beslutning; secure logs eller CEF er ikke innført uten et eksplisitt behov
- [ ] `accessPolicy.inbound` er eksplisitt og speiler auth-kodens validering
- [ ] `accessPolicy.outbound` begrenset til nødvendige tjenester/hoster med cluster/namespace korrekt
- [ ] Secrets kun fra NAIS Console, ingen hardkodede verdier eller prod-secrets lokalt
- [ ] Korrelasjon følger repoets dokumenterte modell; en request-scope-ID presenteres ikke som ende-til-ende over asynkrone grenser uten persistens
- [ ] Behandlingsgrunnlag, retention og sletting er dokumentert for persondata
- [ ] Parameteriserte spørringer, input validert, tilgangskontroll sjekker eierskap (ikke bare gyldig token)
- [ ] `trivy repo .` uten HIGH/CRITICAL, `zizmor` OK, ingen committede secrets
- [ ] Eskalering til sikkerhetschampion er vurdert for nye datakategorier, integrasjoner eller auth-endringer
- [ ] DPIA-behov vurdert (se `references/nav-threat-model.md`) før ny behandling av personopplysninger

## Referanser

| Ressurs | Bruksområde |
|---------|-------------|
| [sikkerhet.nav.no](https://sikkerhet.nav.no) | NAVs Golden Path for sikkerhet |
| `/auth-overview` | JWT-validering, TokenX/Azure AD, `pid`/NAVident/`azp`-claim, Texas-sidecar |
| `/kotlin-ktor` | Repo-definert korrelasjon/MDC, StatusPages/ApiError-feilkontrakt |
| `/flyway-migration` | Migreringer som legger til/endrer PII-kolonner — vurder klassifisering og behandlingsgrunnlag |
| `references/nav-threat-model.md` | Dyp trusselmodellering (STRIDE i NAV-kontekst), DPIA-prosess, audit-logging-krav, Datatilsynet-varsling |
| `references/gdpr-privacy.md` | NAV-spesifikk PII-kategorisering og pekere til DPIA/CEF/retention |
| `references/api-security.md` | NAV-signal: request-/flytkorrelasjon, Nav-Consumer-Id, accessPolicy som primærmekanisme |
