---
name: api-design
description: "Bruk når et Ktor-endepunkt skal legges til eller endres, en konsument skal få tilgang, eller en API-kontrakt brytes — accessPolicy.inbound, TokenX-/Azure AD-validering, versjonering, breaking changes og API-katalog. Triggere: 'nytt endepunkt', 'eksponere API', 'hvem kan kalle', 'breaking change', 'versjonere API', 'aud/issuer', ny route i Routing.kt."
---

# API Design — Nav-konvensjoner

Dette dokumentet dekker **Nav-spesifikke** konvensjoner for API-design i dette repoet. Generelle REST-/HTTP-mønstre er ikke dekket — bruk teamets etablerte praksis.

Brukes typisk i @grillmester fase 1–2 (design) når en kontrakt formes, og i implementasjonsfasen når en route skrives.

## accessPolicy.inbound — hvem får kalle API-et?

API-er eksponert via NAIS må eksplisitt liste hvilke applikasjoner som har tilgang. Ingen implisitt "alle Nav-apper". Navngi team og app.

```yaml
# nais/*.yaml (utdrag)
spec:
  accessPolicy:
    inbound:
      rules:
        - application: saksbehandling-frontend
          namespace: team-vedtak
          cluster: prod-gcp
        - application: oppfolging-api
          namespace: team-oppfolging
          cluster: prod-gcp
```

### Regler
- **Aldri** tom `inbound` på intern-API uten å mene det: tom inbound stenger API-et helt (NAIS krever eksplisitt liste — også for kallere i samme namespace).
- **Aldri** `*` wildcard uten eksplisitt begrunnelse + sikkerhetsreview.
- Koordiner med konsumerende team **før** du legger dem til — de må også ha `outbound`-regel mot deg.
- Fjern konsumenter som ikke lenger bruker API-et (revideres kvartalsvis).

## TokenX- / Azure AD-validering på serversiden

Endepunkter som eksponeres må validere token før forretningslogikk kjøres, med `Authentication`-plugin + `authenticate("...")`-blokk rundt rutene — aldri ad-hoc i hver handler.

**Implementasjon: se `/auth-overview`.** Bruk NAV-biblioteket `no.nav.security:token-validation-ktor-v3` (`tokenValidationSupport`), ikke en rå `jwt()`-plugin, og valider `azp` mot `AZURE_APP_PRE_AUTHORIZED_APPS` for M2M-kallere. Her dekker vi kun **API-kontrakt-siden** av auth.

Kontrakts-relevante valideringsregler (sjekk at de er dekket):
- **Issuer**: matcher TokenX-/Azure-issuer for riktig miljø (dev-gcp / prod-gcp).
- **Audience (`aud`)**: matcher din applikasjons client-id.
- **Signatur**: verifiseres mot JWKS-endpoint.
- **`pid`-claim** (TokenX): brukerens fødselsnummer — autoritativ identitet, **ikke** noe fra request body eller path.
- **`acr`-claim**: sjekk nivå (`idporten-loa-high` / `Level4`) hvis API-et krever høyt innloggingsnivå.
- **`exp`/`nbf`**: standard gyldighetssjekk.

Logg aldri hele tokenet. Behold `/internal/*` (isalive, isready, metrics) uten `authenticate`.

## API-versjonering — koordiner med andre team

Breaking changes på API-er som andre Nav-team konsumerer er et **koordineringsproblem**, ikke bare et teknisk problem. En slik beslutning hører hjemme som ADR i `docs/adr/` — skriv den der så valg og overgangsvindu er sporbart.

### Før brudd-endring
1. **Identifiser konsumenter** via `accessPolicy.inbound` + faktisk trafikk (logger/metrics).
2. **Varsle team eksplisitt** — Slack, e-post eller teamets kanal. Ikke anta at de leser changelog.
3. **Avtal overgangsvindu** — typisk 1–3 måneder der begge versjoner lever parallelt.
4. **Versjoner URL-en** — nytt route-prefiks (`/api/v1/` → `/api/v2/`) i `Routing.kt`, gammel rute beholdes til vinduet er ute.
5. **Deprecering først**: merk gammel versjon som deprecated (gjerne `Deprecation`/`Sunset`-header), gi konsumentene tid.
6. **Logg beslutningen** som ADR i `docs/adr/` og oppdater `docs/context.md` hvis kontrakten er en del av domenespråket.

### Ikke-brudd-endringer (trygge)
- Legge til nye felter i response.
- Gjøre nye request-felter valgfrie (med fornuftig default i data-klassen).
- Legge til nye endpoints.
- Legge til en ny `ErrorType`-verdi — forutsatt at konsumenter deserialiserer feilrespons defensivt.

Disse kan rulles ut uten koordinering, men dokumenter dem.

**Feilkontrakten teller også:** å omdøpe/fjerne en `ErrorType`-verdi eller endre `ApiError`-formen (`status`/`type`/`message`/`path`/`timestamp`) bryter konsumenter som parser feilrespons — behandle det som en brudd-endring (se trinnene over). Implementasjon: `/kotlin-ktor` (references/error-handling.md).

## API-katalog

Registrer API-et i [apikatalog.nav.no](https://apikatalog.nav.no) slik at andre team kan finne det. Særlig viktig for API-er som kan ha bredere nytte enn umiddelbare konsumenter.

## Dokumentasjon — bruk teamets format

Dokumenter API-ene i formatet **teamet allerede bruker** (OpenAPI/Swagger, Postman-collection, Markdown, AsyncAPI for event-drevne / Kafka-baserte API-er). Ikke påtving ett bestemt format. Målet er at konsumenter finner og forstår kontrakten — ikke formatvalget i seg selv.

## Kobling til faseløkka

- Forming av ny kontrakt → stresstest i design-intervju (`/grill-with-docs`), fang ADR + glossar i `.grill/`.
- Sikkerhetssensitive valg (wildcard inbound, ekstern eksponering, brudd-endring) → kjør `grill-inspektor` (kryssmodell-review) før implementasjon.
- Sjekk PLAN.md / VERIFICATION.md i `.grill/` for at kontraktsendringen er dekket av plan og verifikasjon før merge.

## Grenser

### Alltid
- Eksplisitt `accessPolicy.inbound` med navngitte team/apper.
- Token-validering (issuer, audience, signatur, `pid`) i `authenticate`-blokk for eksponerte API-er.
- Koordinere brudd-endringer med konsumerende team før release.
- Aldri PII (FNR, navn) i URL-er eller query params — bruk `pid` fra token.

### Spør først
- Fjerning av konsument fra `accessPolicy.inbound`.
- Brudd-endring i kontrakt (krever ADR i `docs/adr/`).
- Eksponering av API utenfor `cluster` (ekstern tilgang).

### Aldri
- Tom eller wildcard `inbound` uten sikkerhetsreview.
- Stole på brukeridentitet fra request body/path — bruk token-claim.
- Silent breaking changes.
- Logge hele tokens eller PII.
- Endepunkt uten `authenticate` (med mindre det er bevisst åpent, som `/internal/*`).
