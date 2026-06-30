# Auth-beslutningstre — caller-type → mekanisme

Identifiser hvem som initierer forespørselen mot dette Ktor-backendet, og velg auth-mekanisme deretter. Skill mellom innkommende validering (hva dette API-et godtar) og utgående token (hva dette API-et selv henter for å kalle nedstrøms).

## Innkommende — hvem kaller dette API-et

| Kaller                                    | Auth-mekanisme               | Nais-flagg                        |
|-------------------------------------------|------------------------------|-----------------------------------|
| NAV-tjeneste med brukerkontekst (OBO)     | TokenX                       | `tokenx.enabled: true`            |
| NAV-tjeneste uten brukerkontekst (batch)  | Azure AD client_credentials  | `azure.application.enabled: true` |
| Saksbehandler (token fra Azure-frontend)  | Azure AD                     | `azure.application.enabled: true` |
| Innbygger (via frontend/Wonderwall)       | TokenX (frontend veksler ID-porten) | `tokenx.enabled: true`            |
| Ekstern partner / system                  | Maskinporten                 | `maskinporten.enabled: true`      |
| Systembruker (Altinn 3)                   | Maskinporten + systembruker  | `maskinporten.enabled: true`      |

> **Merk Innbygger-flyten:** frontend/BFF (Wonderwall) veksler ID-porten-token til TokenX før kall til dette backend-API-et — backend validerer da et TokenX-token. `idporten.enabled: true` på et rent backend-API er uvanlig (settes kun hvis appen selv mottar ID-porten-token direkte).

## Utgående — dette API-et kaller en annen tjeneste

| Skal brukerens identitet følge med? | Mekanisme                     | Texas `identity_provider` |
|-------------------------------------|-------------------------------|---------------------------|
| Ja (brukerkontekst finnes)          | TokenX exchange (OBO)         | `tokenx`                  |
| Nei (ren maskin-til-maskin)         | Azure AD client_credentials   | `azuread`                 |

## Vanlig feil

Azure client_credentials brukt der brukerkontekst finnes — identiteten tapes og per-bruker-autorisasjon blir umulig.

```
❌ FEIL:
Innbygger → Frontend → [Azure client_credentials] → dette API
   Konsekvens: Mister hvem brukeren er, kan ikke autorisere per bruker

✅ RIKTIG:
Innbygger → Frontend → [TokenX] → dette API → [TokenX exchange] → nedstrøms
   Konsekvens: Brukerens identitet (pid) følger med hele veien
```

## Systembruker (Altinn 3)

Mekanisme i Altinn 3 der eksterne virksomheter oppretter en systembruker som gir tilgang til NAV-tjenester via Maskinporten. Se [Altinn 3 systembruker-dokumentasjon](https://docs.altinn.studio/authentication/what-do-you-get/systemuser/).
