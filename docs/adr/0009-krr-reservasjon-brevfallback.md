# 0009: KRR-reservasjon som beslutningsgate + brevFallback

- Status: besluttet (operasjonaliserer B2/B7/B8; første del av det ugrillede området 5 «Auth & ACL», se `docs/context.md` B62)
- Dato: 2026-07-22
- Relatert: B2/B7/B8/B10/B25/B28/B55, ADR 0001 (domeneblind), issue #22 (epic #15), `/auth-overview`

## Kontekst

`BrukervarselCreate` bærer allerede en valgfri `externalVarsling` (SMS/e-post) og et valgfritt
`brevFallback`-objekt (B8). Selve gaten som skal styre disse ut fra brukerens
kontakt-/reservasjonsstatus i KRR (digdir-krr-proxy) manglet: beslutningskjernen gatet til nå
kun på død (PDL, `DeathGate`). Kommentaren i #20/#22 sier eksplisitt at KRR-reservasjon ikke er
i grunnlaget ennå og at det skal utvides additivt her.

Tre valg må festes:

1. **Auth-mekanisme mot KRR.** TokenX (OBO) vs. Azure AD M2M (`client_credentials`).
2. **Hva utløser fallback** — kun `reservert`, eller den bredere «kan ikke varsles digitalt».
3. **Hvor logikken bor** og hvordan brevet leveres.

## Beslutning

### 1. Auth: Azure AD M2M (client_credentials), IKKE TokenX

Budstikka er en ren Kafka-konsument uten innkommende brukerkontekst — det finnes ikke noe
brukertoken å veksle on-behalf-of. Per `/auth-overview` sitt beslutningstre («NAV-tjeneste uten
brukerkontekst → Azure AD client_credentials») kalles KRR maskin-til-maskin, akkurat som PDL og
dokdist. Issue-teksten sier «TokenX der relevant» — her er den ikke relevant. KRR-klienten
gjenbruker derfor `TexasTokenProvider` (`identity_provider = entra_id`) og et KRR-scope, samme
mønster som `PdlClient`/`DocumentDistributionClient`. `azure.application.enabled` er allerede på
i manifestet (for PDL).

### 2. Fallback-trigger: personen kan ikke varsles digitalt (`kanVarsles = false`)

KRR-feltet `kanVarsles` er false både når personen er reservert mot digital kommunikasjon OG
når personen mangler verifisert digital kontaktkanal. Begge tilfeller betyr det samme for oss:
digital ekstern varsling når ikke fram → send brev i stedet (når `brevFallback` finnes). Vi
modellerer derfor budstikkas nøytrale «reservert»-begrep (B7) som **«kan ikke varsles digitalt»**
= `kanVarsles == false`. Dette er tryggere for innbyggeren enn å se på `reservert` alene (en
person uten kontaktinfo ville ellers verken fått SMS/e-post eller brev), og samsvarer med at
tms/min-side-varsler uansett selv undertrykker ekstern varsling for slike brukere.

Porten (`ReservationLookup`) er domeneblind og eksponerer kun `isReserved(ident): Boolean` med
denne semantikken; KRR-kontrakten lekker aldri inn i domenet (B23).

### 3. Gate i beslutningskjernen; brev via eksisterende BREV-delivery

`ReservationGate` er en `DecisionRule` (B55) som self-selekterer på `BrukervarselCreate` og bare
slår opp KRR når det finnes noe eksternt å styre (`externalVarsling != null || brevFallback != null`)
— andre varianter og rene in-app-brukervarsler slipper uendret gjennom uten KRR-kall. Ved
`isReserved == true` gjør den rene `apply`-halvdelen to ting:

- **Undertrykker ekstern varsling:** BRUKERVARSEL-leveransens innhold transformeres til
  `copy(externalVarsling = null)`. In-app-brukervarselet vises på Min side uansett (B7) — kun
  SMS/e-post fjernes.
- **Legger til brev:** finnes `brevFallback`, syntetiseres en ny BREV-`DeliveryDraft` med
  `BrevCreate(personIdentifier, brevFallback.journalpostId, brevFallback.distributionType)`.
  Den gjenbruker HELE den eksisterende BREV-stien (delivery-rad + `BrevChannelHandler` + dokdist,
  #21) — ingen ny kanal, tabell eller handler.

Rekkefølge i regel-lista: `[DeathGate, ReservationGate]`. Er personen død, short-circuiter
`DeathGate` med `Dropped(DEAD)` før reservasjonstransformasjonen anvendes (ingen brev til død
person). KRR-oppslaget kan likevel ha blitt gjort forgjeves fordi `resolve` kjøres konkurrent —
bevisst avveining (B55). Transient KRR-feil kastes fra `resolve` (I/O) og håndteres av skallet
med backoff, som `PdlClient` (aldri stille tolket som «ikke reservert»).

Ingen skjemaendring: reservasjon resolveres ved beslutning og fryses inn i leveransene; den
persisteres ikke som egen kolonne.

## Konsekvenser

- Ny nedstrøms-avhengighet digdir-krr-proxy (team-rocket): `accessPolicy.outbound` + KRR-scope +
  `KRR_URL`/`KRR_SCOPE` i manifestet, i samme PR som klienten (default-deny).
- fnr sendes til KRR i request (header/body) men aldri til logg (B46); KRR-feilresponser logges
  kun med statuskode, aldri rå body (kan bære PII).
- Ny personopplysningskilde → DPIA/behandlingsprotokoll må dekke KRR-oppslaget før prod (juridisk
  spor, jf. B42 — ikke kode).
- **Verifiseres i dev før prod:** eksakt KRR-endepunkt (`/rest/v1/person` vs. `/rest/v1/personer`),
  namespace (`team-rocket`) og scope-streng må bekreftes mot digdir-krr-proxy sin gjeldende
  kontrakt. Endepunkt-stien ligger i `KRR_URL` (config), og hele KRR-kontrakten er isolert bak
  `ReservationLookup` — en wire-justering rører ikke domenet.

## Vraket

- **TokenX/OBO:** ingen innkommende brukerkontekst å veksle — ikke anvendbart.
- **Trigge kun på `reservert`:** ville latt personer uten digital kontaktkanal falle mellom to
  stoler (verken digitalt varsel eller brev).
- **Egen brev-kanal/handler for fallback:** unødvendig — `BrevCreate` + BREV-delivery finnes og
  round-tripper allerede (E2E #21).
- **La tms håndtere reservasjon implisitt (ikke strippe ekstern selv):** budstikka
  self-operasjonaliserer varselstyringen (B7/B25) → eksplisitt og observerbart hos oss, ikke
  avhengig av nedstrøms sideeffekt.
