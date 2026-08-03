# ADR 0014 — Inbox-hold for sendevindu; FERDIGSTILL kansellerer ventende OPPRETT

- Status: foreslått
- Dato: 2026-08-03
- Relatert: ADR 0008 (lot hold-plassering stå åpen), ADR 0011–0013 (sendevindu),
  issue #166, #27, #15, `docs/ferdigstill.md`, B25/B39/B61

## Kontekst

ADR 0008 hydrerte `inbox_message` og løftet ut `reference`, men lot ett valg stå
åpent (issue #166): hvor en melding som ikke skal sendes ennå holdes tilbake, og
hvor en senere FERDIGSTILL/annullering påføres.

- **Inbox-hold:** hold OPPRETT som `WAIT` på `inbox_message` før deliveries
  materialiseres.
- **Outbox-hold:** materialiser deliveries og hold/kanseller dem i `delivery`
  (f.eks. en `CANCELLED`-state).

To ting avgjør valget:

1. #27 sier «outbox respekterer sendevinduet». Den ordlyden var et **artefakt av
   den pre-0008 parse-frie modellen** (ADR 0002): uten `reference` på inbox-raden
   kunne ikke en FERDIGSTILL finne en ventende OPPRETT, så outbox-hold var eneste
   farbare vei. ADR 0008 løftet `reference` ut på inbox-raden **nettopp for at
   FERDIGSTILL skal kunne matche ubesluttede inbox-rader uten re-parsing**, og
   fjernet dermed den blokkeringen. «Outbox» i #27 var altså ikke et bevisst
   arkitekturvalg av hold-plassering.
2. FERDIGSTILL-før-sending er et **reelt** scenario: en OPPRETT kan holdes over en
   natt eller helg mens en FERDIGSTILL for samme referanse ankommer i mellomtiden
   (sykefraværshendelser ferdigstilles i helgen før vinduet åpner). Begge
   modellene må derfor ha en kanselleringssti — ellers våkner OPPRETT når vinduet
   åpner og sender et varsel som allerede er ferdigstilt.

## Beslutning

Holdet ligger på **inbox** (inbox-hold), og FERDIGSTILL kansellerer en ventende
OPPRETT direkte.

1. En OPPRETT utenfor sendevinduet holdes som `WAIT` på `inbox_message`. Ingen
   delivery materialiseres før vinduet åpner. `next_attempt_time` bærer neste
   åpningstidspunkt; når det passeres, re-claimes raden og behandles normalt.
2. En FERDIGSTILL som treffer en ventende OPPRETT **kansellerer WAIT-inbox-raden
   direkte** (compare-and-set fra `WAIT` til terminal). Ingen delivery lages,
   ingen sending skjer, og ingen løs `INAKTIVER`-delivery opprettes.
3. Matching skjer på `reference` (+ recipient/channel utledet fra `content`),
   slik ADR 0008 la til rette for. Det er den samme selektive match-nøkkelen som
   FERDIGSTILL bruker mot sendte deliveries (B39).
4. In-flight-racen — OPPRETT våkner fra `WAIT` i akkurat samme øyeblikk som
   FERDIGSTILL behandles — lukkes ved at FERDIGSTILL-matchingen tar en **rad-lås
   (`SELECT … FOR UPDATE`) på den matchende OPPRETT-inbox-raden** før den
   bestemmer seg. Da serialiseres den mot OPPRETT-oppvåkningen, som allerede
   holder sin egen inbox-rad låst mens den materialiserer/sender i samme
   transaksjon. Utfallet blir deterministisk: enten kansellerer FERDIGSTILL
   WAIT-raden før OPPRETT sender, eller OPPRETT har allerede sendt og FERDIGSTILL
   faller tilbake til normal `INAKTIVER` mot den sendte deliveryen.
5. `reference`-indeksen på `inbox_message`, som ADR 0008 gjorde betinget av dette
   valget, legges nå til (inbox-hold er valgt).

## Konsekvenser

- ➕ Ingen materialisér-så-lukk-sløsing: en OPPRETT som ferdigstilles før sending
  lager aldri en delivery, og vi slipper en løs `INAKTIVER` mot en delivery som
  aldri ble sendt.
- ➕ Billigere schema: ingen egen `CANCELLED`-delivery-state for holdte meldinger.
- ➕ `reference`-match på inbox var allerede forberedt av ADR 0008; ingen nytt
  konvolutt-felt eller ny header trengs.
- ➖ FERDIGSTILL får **to match-flater**: en ventende `WAIT`-inbox-rad og en
  sendt `delivery`. Beslutnings-workeren må sjekke WAIT-inbox før den faller
  tilbake til delivery-`INAKTIVER`.
- ➖ `WAIT` er en ny `inbox_message`-state som gammel kode ikke kjenner. En
  rollback til pre-`WAIT`-kode ignorerer `WAIT`-rader (de blir liggende til en
  forover-fiks). Utrulling må derfor rulle ut WAIT-**lesing** før WAIT-**skriving**,
  eller akseptere at holdte rader står til koden er på plass igjen.
- ➖ Racen krever rad-lås-disiplin i FERDIGSTILL-matchingen; uten `FOR UPDATE` på
  OPPRETT-raden gjenåpnes vinduet i Beslutning pkt. 4.

## Alternativer vurdert

- **Outbox-hold** (materialiser delivery, hold og kanseller via `CANCELLED`).
  Forkastet: #27s «outbox»-ordlyd var et artefakt av den pre-0008 parse-frie
  modellen, ikke et bevisst valg. Outbox-hold gir materialisér+lukk-sløsing og
  krever en ny `CANCELLED`-delivery-state uten gevinst nå som inbox bærer
  `reference`.
- **Uniform «alltid en delivery»** (materialiser alltid OPPRETT og lukk via
  `INAKTIVER`, også når den holdes). Forkastet av samme grunn: enklere matching,
  men samme sløsing og ingen reell forenkling over inbox-hold med to match-flater.

## Oppfølging (lenkede implementasjons-issues)

Implementeringen deles i selvstendig testbare issues (jf. #166):

- WAIT-oppvåkning skal **ikke** forbruke attempt-budsjettet (venting er ikke et
  feilforsøk; i dag øker `attempt` ved re-claim av `WAIT`).
- Ventårsak lagres i en dedikert kolonne, ikke misbrukt `error_message`.
- FERDIGSTILL-kansellering av WAIT-rad med rad-lås, inkludert
  repository-integrasjonstester for WAIT før/etter åpning, restart, rollback og
  samtidige claims.
- Utrullings-/rollback-strategi for den nye `WAIT`-staten.
