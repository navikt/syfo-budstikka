---
name: security-review
description: "Sikkerhetsgjennomgang av Ktor-backend i no.nav.syfo: PII/FNR/helseopplysninger i logger, secrets, CEF-auditlogg, accessPolicy i NAIS-manifestet, JWT-validering (TokenX/Azure AD), eksterne integrasjoner, DPIA og eskalering til sikkerhetschampion. Brukes før commit/push/PR med sikkerhetsrelevans, eller når noen sier /security-review."
---

# Sikkerhetsgjennomgang — NAV-kontekst

NAV-spesifikk sikkerhetssjekk før commit, push og PR i dette repoet. Generiske OWASP-mønstre (SQLi, XSS, CSRF, injection) forutsettes kjent — dette dokumentet fokuserer på NAV-konteksten: PII-klassifisering, accessPolicy som sikkerhetsmekanisme, og eskalering til sikkerhetschampion. For JWT-validering, claims og auth-oppsett i koden, se `/auth-overview`.

## Flytkobling

Denne skillen brukes typisk i **verifiser**-fasen av @grillmester sin faseløkke, og før PR. Når en gjennomgang avdekker en bindende beslutning (ny datakategori, valgt auth-mekanisme, ny ekstern integrasjon), skal den fanges:

- Sikkerhetsrelevante avveininger → `docs/adr/` (én ADR per beslutning, ikke reåpne avgjorte valg).
- Funn og bevis (trivy/zizmor-output, exit-koder) → `.grill/VERIFICATION.md`.
- Rammer og klassifisering av data tjenesten behandler → `docs/context.md`.

## PII-klassifisering i NAV

NAV behandler personopplysninger med fire beskyttelsesnivåer. Feil klassifisering er den vanligste rotårsaken til alvorlige avvik.

| Nivå | Typiske data | Behandling |
|------|--------------|------------|
| **Strengt fortrolig** | Helseopplysninger, diagnoser, sykmeldinger, voldsutsatte/kode 6, barnevernsdata | Kryptering i ro og transit, streng tilgangsstyring, CEF-auditlogg ved visning (WARN), dedikert DPIA |
| **Fortrolig** | Fødselsnummer (fnr), D-nummer, kode 7, sensitive ytelsesdata | Aldri i standardlogger, CEF-auditlogg ved visning, tilgangsstyring per sak/bruker |
| **Intern** | Navn, adresse, telefon, e-post, ikke-sensitiv ytelsesstatus | Dataminimering, tilgang per tjenstlig behov, retention dokumentert |
| **Åpen** | Offentlig statistikk, anonymiserte aggregater | Normal tilgang; verifiser at anonymiseringen tåler koblingsangrep |

`syfo`-domenet håndterer sykefravær og sykmeldinger — faktumet "en bruker er sykmeldt" er **strengt fortrolig** (implisitt helseinformasjon). Behandle fnr, sykmeldinger og diagnoser deretter.

**Placeholder i kode og dokumentasjon**: Bruk aldri ekte fnr. I eksempler og tester: `00000000000` eller Skatteetatens offisielle testserie (markert eksplisitt som syntetisk). Se `references/nav-threat-model.md` for DPIA-prosess og audit-krav.

### PII i logger

Repoet logger via Logback (`src/main/resources/logback.xml`). Bruk strukturerte felter, aldri PII i meldingsteksten:

```kotlin
// OK — korrelasjons-ID og tema, ingen PII
log.info("Behandler sak", kv("callId", MDC.get("callId")), kv("tema", sak.tema))

// Aldri — FNR, navn, diagnose eller ytelsesdata i standardlogg
log.info("Behandler sak for ${bruker.fnr}")
```

`Nav-Call-Id` settes ved inngang via Ktor `CallId`-pluginen og legges i MDC, slik at hver logglinje korrelerer på tvers av tjenester (se `/kotlin-ktor`). Visning av personopplysninger til NAV-ansatte skal logges i **CEF-format** til auditlog (egen `auditLogger`, ikke standardloggen). Se `references/nav-threat-model.md` for format og hva som skal logges når.

## accessPolicy som first-line defense

`accessPolicy` i NAIS-manifestet (`.nais/`-yaml eller tilsvarende) er første forsvarslinje — ikke en tilleggsmekanisme. Default deny på NAIS-plattformen betyr at glemt regel = brutt tilgang, ikke åpen tilgang. Men feil regel = eksponert tjeneste.

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
- CEF-auditlogg ved visning av personopplysninger (mønster er etablert).
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

Legg bevis (kommando + output + exit-kode) i `.grill/VERIFICATION.md` — ingen «ser trygt ut»-påstander uten ferskt bevis.

## Hemmeligheter

```kotlin
// OK — fra miljø (NAIS injiserer via Console-secret)
val dbPassword = System.getenv("DB_PASSWORD")
    ?: error("DB_PASSWORD mangler")

// Aldri — hardkodet
val dbPassword = "supersecret123"
```

Secrets opprettes i NAIS Console og injiseres via `envFrom`/`filesFrom`. Sjekk også at de ikke havner i `application.yaml`, `gradle.properties` eller version catalog. Kopier aldri prod-secrets lokalt.

## Sjekkliste (NAV-fokus)

- [ ] PII-klassifisering er avklart for all data tjenesten behandler (strengt fortrolig/fortrolig/intern/åpen) og notert i `docs/context.md`
- [ ] Ingen FNR, navn, helse- eller sensitive ytelsesdata i standardlogger
- [ ] CEF-auditlogg dekker visning av personopplysninger til NAV-ansatte
- [ ] `accessPolicy.inbound` er eksplisitt og speiler auth-kodens validering
- [ ] `accessPolicy.outbound` begrenset til nødvendige tjenester/hoster med cluster/namespace korrekt
- [ ] Secrets kun fra NAIS Console, ingen hardkodede verdier eller prod-secrets lokalt
- [ ] `Nav-Call-Id` propageres (CallId-plugin → MDC) for korrelasjon på tvers av tjenester
- [ ] Behandlingsgrunnlag, retention og sletting er dokumentert for persondata
- [ ] Parameteriserte spørringer, input validert, tilgangskontroll sjekker eierskap (ikke bare gyldig token)
- [ ] `trivy repo .` uten HIGH/CRITICAL, `zizmor` OK, ingen committede secrets
- [ ] Eskalering til sikkerhetschampion er vurdert for nye datakategorier, integrasjoner eller auth-endringer
- [ ] DPIA-behov vurdert (se `references/nav-threat-model.md`) før ny behandling av personopplysninger

## Referanser

| Ressurs | Bruksområde |
|---------|-------------|
| [sikkerhet.nav.no](https://sikkerhet.nav.no) | NAVs Golden Path for sikkerhet |
| `security.instructions.md` | Alltid-på (`applyTo: "**"`) sikkerhetsgrenser — supplerer denne on-demand-skillen |
| `/auth-overview` | JWT-validering, TokenX/Azure AD, `pid`/NAVident/`azp`-claim, Texas-sidecar |
| `/kotlin-ktor` | CallId/MDC, StatusPages/ApiError-feilkontrakt |
| `/flyway-migration` | Migreringer som legger til/endrer PII-kolonner — vurder klassifisering og behandlingsgrunnlag |
| `references/nav-threat-model.md` | Dyp trusselmodellering (STRIDE i NAV-kontekst), DPIA-prosess, audit-logging-krav, Datatilsynet-varsling |
| `references/gdpr-privacy.md` | NAV-spesifikk PII-kategorisering og pekere til DPIA/CEF/retention |
| `references/api-security.md` | NAV-signal: Nav-Call-Id, Nav-Consumer-Id, accessPolicy som primærmekanisme |
