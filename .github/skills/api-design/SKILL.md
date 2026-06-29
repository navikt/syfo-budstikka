---
name: api-design
description: "Bruk når et Ktor-endepunkt skal legges til eller endres, en konsument skal få tilgang, eller en API-kontrakt brytes — accessPolicy.inbound, TokenX-/Azure AD-validering, versjonering, breaking changes og API-katalog. Triggere: 'nytt endepunkt', 'eksponere API', 'hvem kan kalle', 'breaking change', 'versjonere API', 'aud/issuer', ny route i Routing.kt."
---

# API Design — Nav-konvensjoner for Ktor-backend

Dette dokumentet dekker **Nav-spesifikke** konvensjoner for API-design i dette Ktor-repoet (`no.nav.syfo`, Netty, NAIS). Generelle REST-/HTTP-mønstre er ikke dekket — bruk teamets etablerte praksis.

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

Endepunkter som eksponeres må validere token før forretningslogikk kjøres. I Ktor gjøres dette med `Authentication`-plugin + `authenticate("...")`-blokk rundt rutene — aldri ad-hoc i hver handler.

```kotlin
// Skille mellom borger-API (TokenX) og system-API (Azure AD).
install(Authentication) {
    jwt("tokenx") {
        verifier(jwkProvider, tokenxIssuer) {
            withAudience(config.tokenxClientId)   // aud = din client-id
        }
        validate { cred -> JWTPrincipal(cred.payload) }
    }
}

routing {
    authenticate("tokenx") {
        get("/api/v1/dokument/{id}") {
            val pid = call.principal<JWTPrincipal>()!!.payload
                .getClaim("pid").asString()   // autoritativ brukeridentitet
            // ... bruk pid, ALDRI fnr fra path/body
        }
    }
}
```

Valideringsregler (rammeverk-uavhengige, men kontroller at de er dekket i `verifier`/`validate`):
- **Issuer**: matcher TokenX-/Azure-issuer for riktig miljø (dev-gcp / prod-gcp).
- **Audience (`aud`)**: matcher din applikasjons client-id.
- **Signatur**: verifiseres mot JWKS-endpoint (cache `JwkProvider`).
- **`pid`-claim** (TokenX): brukerens fødselsnummer — bruk denne som autoritativ identitet, **ikke** noe fra request body eller path.
- **`acr`-claim**: sjekk nivå (`idporten-loa-high` / `Level4`) hvis API-et krever høyt innloggingsnivå.
- **`exp`/`nbf`**: standard gyldighetssjekk (håndteres av `jwt`-plugin).

Logg aldri hele tokenet. Logg `sub`/`jti` for sporbarhet hvis nødvendig. Behold `/internal/*` (isalive, isready, metrics) uten `authenticate`.

## API-versjonering — koordiner med andre team

Breaking changes på API-er som andre Nav-team konsumerer er et **koordineringsproblem**, ikke bare et teknisk problem. En slik beslutning hører hjemme som ADR i `docs/adr/` — skriv den der så valg og overgangsvindu er sporbart.

### Før brudd-endring
1. **Identifiser konsumenter** via `accessPolicy.inbound` + faktisk trafikk (logger/metrics).
2. **Varsle team eksplisitt** — Slack, e-post eller teamets kanal. Ikke anta at de leser changelog.
3. **Avtal overgangsvindu** — typisk 1–3 måneder der begge versjoner lever parallelt.
4. **Versjoner URL-en** — nytt route-prefiks (`/api/v1/` → `/api/v2/`) i `Routing.kt`, gammel rute beholdes til vinduet er ute.
5. **Deprecering først**: merk gammel versjon som deprecated (gjerne `Deprecation`/`Sunset`-header), gi konsumentene tid.
6. **Logg beslutningen** som ADR i `docs/adr/` og oppdater `docs/CONTEXT.md` hvis kontrakten er en del av domenespråket.

### Ikke-brudd-endringer (trygge)
- Legge til nye felter i response.
- Gjøre nye request-felter valgfrie (med fornuftig default i data-klassen).
- Legge til nye endpoints.

Disse kan rulles ut uten koordinering, men dokumenter dem.

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
