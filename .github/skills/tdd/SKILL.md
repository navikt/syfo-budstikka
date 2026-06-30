---
name: tdd
description: Brukes når du skal bygge ny funksjonalitet eller fikse en feil test-først i dette Ktor-backendet — når noen nevner "red-green-refactor", "test først", ber om en reproduksjonstest for en bug, eller vil ha integrasjonstester mot en route/tjeneste. Trigger også når du står foran et nytt endepunkt, en ny domenetjeneste eller en Kafka-/databasenær endring og vil drive frem grensesnittet via tester.
---

# Testdrevet utvikling (Ktor / no.nav.syfo)

## Filosofi

**Kjerneprinsipp**: Tester verifiserer atferd gjennom offentlige grensesnitt, ikke implementasjonsdetaljer. Koden kan endres totalt — testene skal overleve.

**Gode tester** kjører gjennom ekte kodeveier via offentlige API-er. I et Ktor-backend betyr det som regel: gå inn gjennom HTTP-laget med `testApplication`/`client`, eller kall domenetjenesten via dens offentlige funksjon. En god test leser som en spesifikasjon — `\`bruker uten gyldig token får 401\`` forteller nøyaktig hvilken kapabilitet som finnes. Disse testene overlever refaktorering fordi de ikke bryr seg om intern struktur.

**Dårlige tester** er koblet til implementasjon: de mocker interne samarbeidspartnere, tester private funksjoner, eller verifiserer gjennom siden (f.eks. SELECT rett mot Postgres i stedet for å lese tilbake via tjenesten). Varseltegn: testen feiler når du refaktorerer, men atferden er uendret. Døper du om en intern funksjon og tester ryker — testet de implementasjon, ikke atferd.

Se [tester.md](tester.md) for eksempler og [mocking.md](mocking.md) for når og hvordan du mocker systemgrenser.

## Anti-mønster: horisontale skiver

**IKKE skriv alle tester først og deretter all implementasjon.** Det er "horisontal skiving" — å behandle RED som "skriv alle tester" og GREEN som "skriv all kode".

Det produserer dårlige tester:

- Tester skrevet i bulk tester *innbilt* atferd, ikke *faktisk* atferd.
- Du ender med å teste *formen* på ting (datastrukturer, funksjonssignaturer) i stedet for brukerrettet atferd.
- Testene blir ufølsomme for ekte endringer — de består når atferd brytes og feiler når alt er fint.
- Du binder deg til teststruktur før du forstår implementasjonen.

```
FEIL (horisontalt):
  RED:   test1, test2, test3, test4, test5
  GREEN: impl1, impl2, impl3, impl4, impl5

RIKTIG (vertikalt):
  RED→GREEN: test1→impl1
  RED→GREEN: test2→impl2
  RED→GREEN: test3→impl3
```

**Riktig tilnærming**: vertikale skiver via tracer bullets. Én test → én implementasjon → gjenta. Hver test svarer på det du nettopp lærte av forrige syklus.

## Arbeidsflyt

### 1. Planlegg

Les `docs/CONTEXT.md` hvis den finnes, så testnavn og grensesnittvokabular matcher domenespråket. Respekter besluttede valg i `docs/adr/` for området du rører. Følger du en plan fra `@grillmester`, hold deg til `.grill/PLAN.md` og kryss av atferdene der.

Før du skriver kode:

- [ ] Avklar med bruker hvilke grensesnittendringer som trengs (ny route, ny tjenestefunksjon, ny kontrakt)
- [ ] Avklar hvilke atferder som skal testes, og prioriter
- [ ] Se etter dype moduler — lite grensesnitt, dyp implementasjon — så tjenesten blir lett å teste utenfra
- [ ] List atferdene som skal testes (ikke implementasjonssteg)
- [ ] Få brukerens godkjenning

Spør: "Hvilket offentlig grensesnitt skal vi eksponere? Hvilke atferder er viktigst å teste?"

**Du kan ikke teste alt.** Avklar nøyaktig hvilke atferder som betyr mest. Bruk testkrefter på kritiske veier og kompleks logikk — autorisasjon, valideringsregler, tilstandsoverganger — ikke hver tenkelige edge case.

### 2. Tracer bullet

Skriv ÉN test som bekrefter ÉN ting end-to-end gjennom det offentlige grensesnittet:

```
RED:   Skriv test for første atferd → feiler
GREEN: Minimal kode for å bestå → bestått
```

For et nytt endepunkt er tracer bullet typisk en `testApplication`-test som treffer ruten og forventer riktig status. Den beviser at hele veien — routing, modul-oppsett, respons — henger sammen.

### 3. Inkrementell loop

For hver gjenværende atferd:

```
RED:   Neste test → feiler
GREEN: Minimal kode → bestått
```

Regler:

- Én test om gangen
- Bare nok kode til å bestå gjeldende test
- Ikke forutse fremtidige tester
- Hold testene på observerbar atferd

Kjør raskt og fokusert mens du jobber:

```bash
./gradlew test --tests "no.nav.syfo.<KlasseNavn>"
echo "exit: $?"
```

En GREEN-påstand krever ferskt bevis i samme melding — kommando + output + exit-kode. Uten det: UVERIFISERT.

### 4. Refaktorer

Når alle tester består, se etter [refaktoreringskandidater](refaktorering.md):

- [ ] Ekstraher duplisering
- [ ] Dypne moduler — flytt kompleksitet bak enkle grensesnitt
- [ ] Bruk SOLID der det faller naturlig
- [ ] Vurder hva ny kode avslører om eksisterende kode
- [ ] Kjør tester etter hvert refaktoreringssteg

**Aldri refaktorer mens RED.** Kom til GREEN først.

Når implementasjonen er ferdig og grønn, oppdater `.grill/VERIFICATION.md` med hva som faktisk ble verifisert (kommandoen som ble kjørt og resultatet), slik at `@grillmester`-løkka kan lukke fasen.

## Sjekkliste per syklus

```
[ ] Test beskriver atferd, ikke implementasjon
[ ] Test bruker kun offentlig grensesnitt
[ ] Test ville overlevd intern refaktorering
[ ] Kode er minimal for denne testen
[ ] Ingen spekulative funksjoner lagt til
[ ] GREEN bevist med ferskt kommando + output + exit-kode i samme melding (ellers UVERIFISERT)
```

## Bugfiks er TDD

En feilretting starter med en **reproduksjonstest**: skriv en test som feiler fordi feilen finnes (RED), fiks så til den blir grønn (GREEN). Da har du både bevist feilen og hindret regresjon. Skriv aldri fiksen først.
