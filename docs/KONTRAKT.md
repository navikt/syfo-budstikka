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

enum class AgMeldingstype { BESKJED, OPPGAVE }        // ⟡ nøytral (beskjed vs oppgave) — bekreft

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

### 4. Arbeidsgivervarsel — Min side arbeidsgiver / Altinn  ⟡ MOTTAKER-MODELL GJENSTÅR
```kotlin
data class ArbeidsgivervarselOpprett(
    val orgnummer: Orgnummer,
    val mottaker: AgMottaker,                       // ⟡ ÅPEN: sealed NærmesteLeder | AltinnRessurs (+ fallback?)
    val merkelapp: Merkelapp,                        // B30: typet LUKKET enum (kategori), ikke streng
    val tekst: String,                              // skjermtekst (bjella / sak-tidslinje)
    val lenke: String,
    val eksternVarsling: EksternVarsling? = null,   // B29: epostTittel/epostTekst/smsTekst (ren tekst, budstikka saniterer)
    val meldingstype: AgMeldingstype = AgMeldingstype.BESKJED,  // ⟡ bekreft
    val sakstilknytning: Sakstilknytning? = null,   // B31: konsumentens sak (→ grupperingsid); konsument eier saken
    val synligTom: Instant? = null,
    val sendevindu: Sendevindu? = null,             // B25
) : Hendelsesinnhold { override val partisjonsnokkel get() = orgnummer.value }
```
**Løst denne runden:**
- E-post (B29): budstikka eier ikke mal; konsument oppgir ren-tekst `epostTittel`/`epostTekst`/
  `smsTekst` via `eksternVarsling`; budstikka saniterer; plattformen nedstrøms brander.
- Merkelapp (B30): typet LUKKET enum i delt kontraktlib (kategori, ingen oppførsel); fager-
  registeret er håndhevelsen (`kanSendeTil`), enumen er DX-laget; lukket form tvinger synk.
- Sak (B31): konsumenten eier saken → valgfri `sakstilknytning` (sakId → grupperingsid).

**Gjenstår (⟡):**
- Mottaker-modell: sealed `AgMottaker` = `NærmesteLeder` | `AltinnRessurs` — én variant med
  sealed valg. Research: to separate stier i dag, ALDRI kombinert; INGEN NL→Altinn-fallback
  finnes. Skal budstikka innføre en valgfri Altinn-fallback (speiler brevFallback B8), eller
  beholde separasjonen?
- `meldingstype` BESKJED|OPPGAVE: dekker den nøytrale enumen behovet?

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

## FERDIGSTILL / lukking
```kotlin
data class Inaktiver(
    val kanal: LukkbarKanal,                    // uten BREV → B21 i typen
    val mottakerident: String,                  // for partisjonsnøkkel + matching
) : Hendelsesinnhold { override val partisjonsnokkel get() = mottakerident }

enum class LukkbarKanal { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL }
```
⟡ `Inaktiver` trenger mottaker for partisjonsnøkkel + matching, men mottakertypen
varierer pr. kanal. To valg: (a) felles thin variant med generisk `mottakerident: String`
(matcher lagret rad på (referanse, mottaker_id, kanal)) — bryter litt med typede
value-class-mottakere; (b) én Inaktiver-variant pr. kanal med typet mottaker. B22 valgte
felles thin → (a). Bekreft at generisk ident er greit her (vi matcher kun en lagret rad).

## Åpne spørsmål til review
1. ~~Ledervarsel: konsument vs budstikka-oppslag av NL~~ → **LØST (B24): budstikka resolver.**
2. Arbeidsgivervarsel (#4): e-post (B29), merkelapp (B30) og sak (B31) LØST. Gjenstår: mottaker-modell (`NærmesteLeder` | `AltinnRessurs` + evt. fallback) + `meldingstype`. ⟡
3. Inaktiver: generisk `mottakerident: String` vs typet pr. kanal. ⟡
4. Varseltype-enum (BESKJED/OPPGAVE) og Meldingsvariant: dekker de behovet?
5. Felles `tekst`/`lenke` — bør de trekkes ut i en delt `Innholdstekst`-type?
