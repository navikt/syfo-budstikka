---
name: to-prd
description: "Bruk når en ferdig-grillet idé eller design skal støpes til et PRD og legges på issue-trackeren — UTEN nytt intervju, bare syntese av det som allerede er avklart i samtalen eller den aktive oppgaven. @grillmester rett etter design/plan-fasen, eller når noen sier 'skriv et PRD', 'lag kravspec', 'oppsummer dette til en sak'."
---

# to-prd

Tar samtalekonteksten og den aktive oppgaven og produserer et PRD som publiseres
til issue-trackeren. Les en oppgavelokal `.grill/`-fil bare når den kallende
arbeidsflyten eksplisitt har valgt den. **Ikke intervju brukeren** — syntetisér det dere
allerede har kommet frem til. Mangler grunnlaget (uklart problem, ingen
avklarte beslutninger), stopp og anbefal `/grilling` i stedet for å gjette. Hvis
varige begreper eller beslutninger bør dokumenteres under avklaringen, anbefal
det dokumenterte løpet og vent på brukerens valg.

## Forutsetning: les de avklarte kildene først
Dette er et seint steg i Grillmester sin faseløkke. Før du skriver, les det som allerede er avklart:
- samtalen, oppgaven/parent-issuet og aktiv plan — valgt scope og ferdig-når-kriterier
- eksplisitt relevante topic-dokumenter og ADR-er — vedlikeholdt detalj; tolk
  ADR-status via `docs/agents/domain.md`
- `docs/glossary.md` — domenespråket; **bruk disse begrepene konsekvent** i hele PRD-et
- ferskt implementeringsbevis fra den aktive oppgaven, hvis det faktisk er relevant

Les `docs/context.md` bare hvis PRD-et trenger repository-orientering eller overordnet status.

PRD-et skal være en trofast syntese av dette, ikke en ny idé.

## Prosess
1. **Utforsk repoet** for nåtilstand hvis du ikke allerede har gjort det (`no.nav.syfo`-pakkestruktur, ruter, Flyway-migreringer, Kafka-konsumenter, `nais/`-config). Respekter eksisterende ADR-er i området du berører.

2. **Skjær sømmene (test-seams).** Pek på hvor funksjonaliteten testes. Foretrekk eksisterende sømmer fremfor nye, og legg dem så høyt som mulig. I et Ktor-backend betyr det typisk:
   - Rute-/applikasjonsnivå via `testApplication { ... }` med `client` mot ekte ruter — den høyeste, mest verdifulle sømmen.
   - Repository/DB mot Postgres i Testcontainers, ikke mocket JDBC.
   - Kafka-konsument testet på handler-funksjonen med en konstruert `ConsumerRecord`, ikke mot ekte broker der det går.

   Færre sømmer er bedre — ideelt én. **Bekreft sømmene med brukeren** før du skriver PRD-et.

3. **Skriv PRD-et** etter malen under og publisér til issue-trackeren. Sett triage-etiketten `klar-for-agent` (ingen videre triage nødvendig).

<prd-mal>

## Problemstilling
Problemet brukeren / fagteamet står i, fra deres perspektiv. Ikke teknisk løsning her.

## Løsning
Løsningen på problemet, fra brukerens perspektiv.

## Brukerhistorier
En LANG, nummerert liste. Hver historie på formen:

1. Som en <aktør>, vil jeg <funksjon>, slik at <gevinst>

<eksempel>
1. Som saksbehandler vil jeg se status på en sykmelding, slik at jeg kan følge opp riktig sak til rett tid.
</eksempel>

Lista skal være uttømmende og dekke alle sider av funksjonaliteten — inkludert NAV-spesifikke aktører (innbygger, saksbehandler, konsumerende team, drift/vakt).

## Implementeringsbeslutninger
Beslutninger som er tatt. Kan inkludere:
- Moduler / `no.nav.syfo`-pakker som bygges eller endres
- Endrede grensesnitt (rute-kontrakter, request/response-DTO-er)
- Auth-modell: TokenX / Azure AD, og `accessPolicy` mot konsumerende eller kallende team i NAIS
- Skjemaendringer (Flyway-migrering) og Kafka-topic/-kontrakt (nøkkel, schema, idempotens/replay)
- Feilkontrakt (StatusPages / ApiError) og oppførsel når avhengigheter er nede
- Observability: metrikker, logging (PII-hensyn), tracing fra dag én
- Arkitekturvalg — pek til relevant `docs/adr/NNNN-*.md` i stedet for å gjenta begrunnelsen

**Ikke** inkluder konkrete filstier eller kodeutdrag — de blir utdaterte fort.

Unntak: encoder en kort snutt en beslutning mer presist enn prosa (datamodell/`data class`, schema, tilstandsmaskin), inline den i den aktuelle beslutningen og marker at den er en beslutnings-skisse, ikke kjørbar kode. Trim til det beslutnings-bærende.

## Testbeslutninger
- Hva som kjennetegner en god test her: test ekstern oppførsel (ruter, kontrakter), ikke implementasjonsdetaljer.
- Hvilke moduler/sømmer som testes (fra steg 2).
- Forarbeid / prior art: tilsvarende tester i repoet (`testApplication`-oppsett, Testcontainers-Postgres, Kafka-handler-tester) som den nye testen skal ligne på.
- Deterministisk gate: `./gradlew test` må være grønn — ingen «ser riktig ut».

## Utenfor scope
Hva dette PRD-et bevisst ikke dekker.

## Videre notater
Eventuelle åpne spørsmål, beslutningskandidater eller avhengigheter mot andre
team. Når en kandidat passerer ADR-gaten, anbefal dokumentert løp og vent på
brukerens valg før `/domain-modeling` skriver.

</prd-mal>

## Etterpå
PRD-et er inngangen til oppstykking i konkrete saker. Når det er publisert: bruk `/to-issues` for å bryte det ned i uavhengig-gripbare issues via vertikale tracer-bullet-snitt.
