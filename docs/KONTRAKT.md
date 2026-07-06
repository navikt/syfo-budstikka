# Kontrakt / kanal-DTO-er — syfo-budstikka (UTKAST)

> **Status: UTKAST for review.** Strukturen (sealed + operasjon i typen, jf. B22) er
> låst. Feltene er forslag — vi finmodellerer den nøytrale abstraksjonen (B23) til den
> dekker konsumentbehovet enkelt. Åpne spørsmål er markert ⟡.

## Prinsipp (B23)
Budstikka eier sin egen nøytrale modell. Den speiler IKKE tms/dokdist/notifikasjon-
produsent-api. Hvert felt finnes fordi en konsument trenger det — ikke fordi nedstrøms
har det. Intern mapping til nedstrøms er et anti-corruption-lag.

## Konvolutt
```kotlin
data class Varselbestilling(
    val eventId: UUID,            // dedup (B4)
    val referanse: String,        // kobler OPPRETT/FERDIGSTILL (B4)
    val innhold: Hendelsesinnhold // sealed — bærer mottaker + operasjon
)

sealed interface Hendelsesinnhold {
    val partisjonsnokkel: String  // accessor for Kafka-nøkkel (B5), ikke delt Mottaker-hierarki (B9)
}
```
trace_id kommer via Kafka-header (B17), ikke i payload.

## Felles byggesteiner
```kotlin
@JvmInline value class Personident(val value: String) { /* 11 siffer, toString="***" (B9) */ }
@JvmInline value class Orgnummer(val value: String)   { /* 9 siffer */ }

data class EksternVarsling(                    // vår egen modell (B23), mappes til tms
    val kanaler: Set<EksternKanal> = setOf(EksternKanal.SMS, EksternKanal.EPOST),
    val smsTekst: String? = null,              // null = NAV-standardtekst
    val epostTittel: String? = null,
    val epostTekst: String? = null,
)
enum class EksternKanal { SMS, EPOST }

data class BrevFallback(                        // B8: tilstedeværelse = send brev v/ reservasjon
    val journalpostId: String,
    val distribusjonstype: Distribusjonstype = Distribusjonstype.VIKTIG,
)
enum class Distribusjonstype { VIKTIG, ANNET }

// Sendevindu (B25) — nøytralt begrep, self-operasjonalisert i outbox.
// Default settes av budstikka: NKS_AAPNINGSTID for eksternbærende leveranser,
// LOEPENDE for brev/mikrofrontend/ren in-app. Konsument kan overstyre pr. hendelse.
enum class Sendevindu { LOEPENDE, NKS_AAPNINGSTID }   // utvidbar (DAGTID, ...)

// Merkelapp (B30) — typet LUKKET enum i delt kontraktlib. KATEGORI, ikke oppførsel:
// budstikka forgrener ALDRI på den (ingen when(merkelapp)), bærer den kun til produsent-api.
// Lukket form tvinger fager-registrering (PRODUSENT_REGISTER.tillatteMerkelapper) og
// budstikka-onboarding til å skje sammen → holder listene i synk. Utvides ved onboarding.
enum class Merkelapp { DIALOGMOETE, OPPFOELGING }     // mappes til produsent-api-streng (B23)

enum class AltinnRessursId { DIALOGMOETE }            // B32: → produsent-api ressursId "nav_syfo_dialogmote"; register-håndhevet (B30-logikk)

enum class AgMeldingstype { BESKJED, OPPGAVE }        // B33: nøytral, separat fra Brukervarsels Varseltype; frist/påminnelse utsatt

data class Sakstilknytning(                            // B31: konsumenten eier saken
    val sakId: String,                                 // konsumentens id → grupperingsid nedstrøms
)
```

## OPPRETT-varianter (rike, kanalspesifikke)

### 1. Brukervarsel — sykmeldt, Min side
```kotlin
data class BrukervarselOpprett(
    val personident: Personident,
    val varseltype: Varseltype,                // BESKJED | OPPGAVE (vår enum)
    val tekst: String,                         // skjermtekst på Min side
    val lenke: String? = null,
    val synligTom: Instant? = null,            // konsument eier (B1)
    val eksternVarsling: EksternVarsling? = null,  // null = kun Min side
    val brevFallback: BrevFallback? = null,    // B8
    val sendevindu: Sendevindu? = null,        // B25: null = budstikka-default
) : Hendelsesinnhold { override val partisjonsnokkel get() = personident.value }
```

### 2. Ledervarsel — nærmeste leder, Dine Sykmeldte
```kotlin
data class LedervarselOpprett(
    val sykmeldt: Personident,
    val orgnummer: Orgnummer,
    val tekst: String,
    val lenke: String? = null,
    val synligTom: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val sendevindu: Sendevindu? = null,        // B25
) : Hendelsesinnhold { override val partisjonsnokkel get() = sykmeldt.value }
```
**B24: budstikka resolver nærmeste leder selv.** Kontrakten bærer `(sykmeldt, orgnummer)`
— IKKE NL-fnr. Budstikka slår opp aktiv leder i narmesteleder-registeret i Beslutning-
fasen (som KRR/PDL). Partisjonsnøkkel = `sykmeldt` (stabil anker; NL er ukjent ved
publisering og kan byttes). Eliminerer dagens dobbeltoppslag i esyfovarsel.

### 3. Ditt sykefravær-melding — sykmeldt
```kotlin
data class DittSykefravaerOpprett(
    val personident: Personident,
    val tekst: String,
    val lenke: String? = null,
    val variant: Meldingsvariant = Meldingsvariant.INFO,  // INFO | VIKTIG
    val synligTom: Instant? = null,
) : Hendelsesinnhold { override val partisjonsnokkel get() = personident.value }
```

### 4. Arbeidsgivervarsel — Min side arbeidsgiver / Altinn
```kotlin
data class ArbeidsgivervarselOpprett(
    val orgnummer: Orgnummer,                       // virksomhet (underenhet) — alltid til stede
    val mottaker: AgMottaker,                       // B32: sealed valg (stiene kombineres aldri)
    val merkelapp: Merkelapp,                        // B30: typet LUKKET enum (kategori)
    val tekst: String,                              // skjermtekst (bjella / sak-tidslinje)
    val lenke: String,
    val eksternVarsling: EksternVarsling? = null,   // B29: epostTittel/epostTekst/smsTekst (ren tekst, budstikka saniterer)
    val meldingstype: AgMeldingstype = AgMeldingstype.BESKJED,  // B33 (OPPGAVE-lukking → Inaktiver, ⟡ #3)
    val sakstilknytning: Sakstilknytning? = null,   // B31: konsumentens sak (→ grupperingsid); konsument eier saken
    val synligTom: Instant? = null,
    val sendevindu: Sendevindu? = null,             // B25
) : Hendelsesinnhold { override val partisjonsnokkel get() = orgnummer.value }

// B32 — de to stiene kombineres ALDRI (research) → sealed valg, ikke separate hendelsesvarianter.
sealed interface AgMottaker
data class NarmesteLeder(val sykmeldt: Personident) : AgMottaker   // budstikka resolver NL (B24) fra (sykmeldt, orgnummer); én personlig mottaker
data class AltinnRessurs(val ressurs: AltinnRessursId) : AgMottaker // → alle med Altinn-rollen ved virksomheten; ressursId typet (B30)
// Opt-in altinnFallback (NL→Altinn ved manglende leder) er BESLUTTET men UTSATT (fase 2,
// nice-to-have — esyfovarsel har det ikke i dag). Legges til NarmesteLeder som valgfritt felt
// når implementert (non-breaking). Utelates fra v1 for å unngå falsk affordance.
// V1-default = OBSERVERBAR drop ved manglende NL (metrikk/alert), ikke stille.
```
**Løst:**
- E-post (B29): budstikka eier ikke mal; konsument oppgir ren-tekst `epostTittel`/`epostTekst`/
  `smsTekst` via `eksternVarsling`; budstikka saniterer; plattformen nedstrøms brander.
- Merkelapp (B30): typet LUKKET enum i delt kontraktlib (kategori, ingen oppførsel); fager-
  registeret er håndhevelsen (`kanSendeTil`), enumen er DX-laget; lukket form tvinger synk.
- Sak (B31): konsumenten eier saken → valgfri `sakstilknytning` (sakId → grupperingsid).
- Mottaker-modell (B32): sealed `AgMottaker` = `NarmesteLeder` | `AltinnRessurs` (aldri kombinert).
  NL resolveres av budstikka (B24); `ressursId` typet (B30). Opt-in Altinn-fallback ved manglende
  NL er BESLUTTET (mottaker-utvidelse ≠ brevFallback → konsument eier samtykke) men UTSATT (fase 2);
  v1 = observerbar drop.
- Meldingstype (B33): nøytral `AgMeldingstype { BESKJED, OPPGAVE }`, separat fra Brukervarsel;
  frist/påminnelse utsatt (YAGNI).

**AG ferdig-grillet.** Eneste rest er en kobling til felles Inaktiver (⟡ #3): en OPPGAVE lukkes
trolig via `oppgaveUtført` (ikke `hardDelete`) — løses i Inaktiver-grillingen.

> **Funn fra esyfovarsel (research 2026-07) — grunnlag for AG-grillingen:**
> - **To separate mottaker-stier i dag, ALDRI kombinert:** (1) `NaermesteLederMottaker`
>   (personlig NL via fnr+ansattfnr; ekstern e-post til NLs registrerte adresse — INGEN
>   SMS), (2) `AltinnRessursMottaker` (`ressursId`, f.eks. `nav_syfo_dialogmote`; ekstern
>   SMS + e-post til alle med Altinn-rollen). Bruker Altinn 3-ressurs, IKKE serviceCode.
> - **INGEN NL→Altinn-fallback finnes i dag:** mangler NL-relasjon → esyfovarsel DROPPER
>   varselet (hard retur, logg). To helt separate kodegrener.
> - **Mye er domenespesifikt og MÅ komme fra konsument:** `merkelapp` ("Dialogmøte"/
>   "Oppfølging"), `messageText`/`smsTekst`, `epostTittel`/`epostHtmlBody`, `meldingstype`
>   (BESKJED vs OPPGAVE), sak-tittel (inneholder personnavn), `grupperingsid`, `lenke`/
>   `ressursUrl`, `ressursId`, `hardDeleteDate`. Sak/Kalenderavtale-livsløp er i dag tett
>   koblet til dialogmøte-domenet → bør ligge hos konsument i nytt design.
> - **Ruteren kan eie generisk:** NL-oppslag (B24), bygging av riktig MottakerInput,
>   ekstern varsling, sendevindu (B25), idempotens, retry, DB-persistens.
> - **Lukking i dag:** NL-sti = `hardDeleteNotifikasjon(merkelapp, eksternReferanse)`;
>   Altinn-sti = `nyStatusSakByGrupperingsid(grupperingsid, merkelapp → FERDIG)`.
>   Matchenøkkel varierer → relevant for hvordan Inaktiver targeter AG (jf. ⟡ Inaktiver).

### 5. Brev — sykmeldt, fysisk  (INGEN ferdigstill, jf. B3/B21)
```kotlin
data class BrevOpprett(
    val personident: Personident,
    val journalpostId: String,                 // konsument har opprettet journalpost
    val distribusjonstype: Distribusjonstype = Distribusjonstype.VIKTIG,
) : Hendelsesinnhold { override val partisjonsnokkel get() = personident.value }
```

### 6. Mikrofrontend — sykmeldt, synlighet på Min side
```kotlin
data class MikrofrontendAktiver(
    val personident: Personident,
    val mikrofrontendId: String,               // konsument oppgir hvilken (domeneblind)
    val synligTom: Instant? = null,
) : Hendelsesinnhold { override val partisjonsnokkel get() = personident.value }

data class MikrofrontendDeaktiver(             // mikrofrontendens «ferdigstill»
    val personident: Personident,
    val mikrofrontendId: String,
) : Hendelsesinnhold { override val partisjonsnokkel get() = personident.value }
```
⟡ Mikrofrontend er synlighet, ikke et varsel. Egne aktiver/deaktiver-varianter holder
det utenfor den generelle Inaktiver-mekanismen. OK?

## FERDIGSTILL / lukking (B38, B39)
Typet variant PR. LUKKBAR KANAL — kanal er implisitt i typen, matchnøkkelen er typet
(bevarer PII-maskering, B9) og ulovlige `(kanal, nøkkel)`-par er urepresenterbare (B38).
Hendelsen er THIN: kun `referanse` + typet nøkkel. Lukkeoperasjonen nedstrøms bæres ALDRI
av hendelsen — budstikka avleder den fra den lagrede OPPRETT-raden (B39).

```kotlin
data class BrukervarselInaktiver(
    val referanse: String,
    val sykmeldt: Personident,                  // matchnøkkel = OPPRETT-partisjonsanker
) : Hendelsesinnhold { override val partisjonsnokkel get() = sykmeldt.value }

data class LedervarselInaktiver(
    val referanse: String,
    val sykmeldt: Personident,                  // B24: sykmeldt, IKKE NL-fnr (ukjent for konsument)
) : Hendelsesinnhold { override val partisjonsnokkel get() = sykmeldt.value }

data class DittSykefravaerInaktiver(
    val referanse: String,
    val sykmeldt: Personident,
) : Hendelsesinnhold { override val partisjonsnokkel get() = sykmeldt.value }

data class ArbeidsgivervarselInaktiver(
    val referanse: String,
    val orgnummer: Orgnummer,                   // matchnøkkel = virksomhet (B32)
) : Hendelsesinnhold { override val partisjonsnokkel get() = orgnummer.value }
```
**Matchnøkkel** = OPPRETTs partisjonsanker (det konsumenten kjenner ved OPPRETT), ikke den
faktiske resolverte mottakeren. Match mot lagret rad: `(referanse, mottaker_id, kanal)`.
BREV er urepresenterbart (ingen `BrevInaktiver` — B3/B21). Mikrofrontend har egne
aktiver/deaktiver-varianter (§ over), utenfor denne mekanismen.

**Lukkeoperasjon (B39):** avledes fra lagret OPPRETT-rad. Beslutnings-workeren (B28) finner
matchende OPPRETT-leveranse, `decide()` fryser lukkeparametrene (`meldingstype`, sti NL/Altinn,
`ekstern_respons_id`, `grupperingsid`) onto INAKTIVER-leveransen, og `Kanalhandler` (B27)
dispatcher på disse LAGREDE TEKNISKE attributtene — AG: OPPGAVE→`oppgaveUtført`,
BESKJED→`hardDelete`, sak→`nyStatusSak(FERDIG)` — aldri på domenetype (domeneblind, B1/B30).

## Åpne spørsmål til review
1. ~~Ledervarsel: konsument vs budstikka-oppslag av NL~~ → **LØST (B24): budstikka resolver.**
2. Arbeidsgivervarsel (#4): e-post (B29), merkelapp (B30), sak (B31), mottaker-modell (B32), meldingstype (B33) LØST — **AG ferdig-grillet**. Rest-kobling til Inaktiver, se #3.
3. ~~Inaktiver: generisk `mottakerident: String` vs typet pr. kanal~~ → **LØST (B38/B39): typet Inaktiver pr. kanal (thin), lukkeoperasjon avledes fra lagret OPPRETT-rad.**
4. Varseltype-enum (BESKJED/OPPGAVE) og Meldingsvariant: dekker de behovet?
5. Felles `tekst`/`lenke` — bør de trekkes ut i en delt `Innholdstekst`-type?
