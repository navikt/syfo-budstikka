---
name: improve-codebase-architecture
description: "Bruk når brukeren vil forbedre arkitekturen, finne refaktoreringsmuligheter, gjøre grunne moduler dypere, konsolidere tett koblede moduler, eller gjøre Ktor-koden mer testbar og lettere å navigere for både folk og AI. Også når noen sier 'forbedre arkitekturen', 'finn refaktoreringsmuligheter', 'denne koden er rotete/vanskelig å teste', 'for mange tynne lag', eller 'rute → service → klient-kjedene er uoversiktlige'."
---

# improve-codebase-architecture

Avdekk arkitektonisk friksjon i dette repoet og foreslå **fordypningsmuligheter** — refaktoreringer som gjør grunne moduler dype. Målet er testbarhet og at både mennesker og AI lett kan navigere koden.

**Rolle:** dette _finner_ kandidater (oppdagelse). Design grensesnittet på en
valgt kandidat inline med to genuint ulike alternativer og avhør valget med
`/grilling`. Når varige begreper eller beslutninger bør dokumenteres, anbefal
det dokumenterte løpet og vent på brukerens valg. Bruk
`/architecture-review` for NAV-review og `/domain-modeling` etter valgt
dokumentert løp.

Skillen er **informert av** domenemodellen og besluttede valg, og bygger på et delt arkitekturvokabular:

- `docs/glossary.md` gir navn til gode sømmer i domenet; relevante
  topic-dokumenter beskriver vedlikeholdt detalj. Tolk ADR-status via
  `docs/agents/domain.md`, og ikke re-litiger bindende valg uten grunn.
- Dette er @grillmester sin oppdagelsesfase: funn herfra mates inn i naturlig
  grilling (`/grilling`), aktiv plan og verifisering.

## Vokabular

Bruk dyp-modul-vokabularet presist: en **modul** skjuler en **implementasjon** bak et lite **grensesnitt**; **dybde** er mengden kompleksitet grensesnittet skjuler. En **søm** er stedet modulen kan skilles fra en **adapter**. **Lokalitet** holder relatert kunnskap samlet, og **leverage** er hvor mye kompleksitet ett grensesnitt bærer. Ikke drift til «komponent», «service», «lag» eller «API» når disse mer presise ordene passer.

**Slettetesten** (det operative verktøyet for oppdagelse): ville det å slette modulen *konsentrere* kompleksitet (bra — den var grunn) eller bare flytte den (da var den ekte)? Et «ja, konsentrerer» er signalet du jakter på.

## Prosess

### 1. Utforsk

Følg den smale lasterekkefølgen i `docs/agents/domain.md`: les glossaret når domenespråket er relevant, og bare topic-dokumentene og ADR-ene som berører området.

Gå så gjennom kodebasen organisk — ikke følg rigide heuristikker. Noter hvor du opplever friksjon. I et Ktor-backend ser fordypningsmuligheter typisk slik ut:

- **Tynne lag i kjede:** `Route { } → Service → Repository → klient` der hvert ledd nesten bare videresender. Slå sammen til én dyp modul.
- **DTO-mappere ekstrahert kun for testbarhet:** `toDto()` / `fromDb()`-funksjoner som er rene, men der de ekte feilene sitter i hvordan de kalles (ingen lokalitet).
- **Grunne klient-wrappere:** en `HttpClient`-kall pakket i en klasse som ikke gjemmer noe — TokenX/Azure AD-token, retry og feilkontrakt lekker ut til kallstedet.
- **Spredt Kafka-logikk:** konsument, deserialisering, idempotens/replay-håndtering og forretningslogikk fordelt over flere moduler uten én søm.
- **Lekkende databaselag:** SQL/`DataSource`/Flyway-detaljer som siver ut av repository-modulen.
- **Vanskelig å teste gjennom grensesnittet:** moduler som krever oppspinning av halve Ktor-stacken for å testes — tegn på at sømmen sitter feil sted.

Bruk slettetesten på alt du mistenker er grunt.

### 2. Presenter kandidatene som en HTML-rapport

Skriv en selvstendig HTML-fil til OS-temp slik at ingenting havner i repoet. Resolv temp-katalog fra `$TMPDIR` med `/tmp` som fallback, og skriv til `<tmpdir>/arkitektur-review-<timestamp>.html`. Åpne den for brukeren (`open <sti>` på macOS, `xdg-open <sti>` på Linux) og oppgi den absolutte stien.

Hver kandidat får et kort med: **Filer**, **Problem** (én setning), **Løsning** (én setning), **Gevinster** (punktliste i vokabularet — lokalitet/leverage/testflate), **Før/etter-diagram**, og **Anbefalingsstyrke** (`Sterk`, `Verdt å utforske`, `Spekulativ`). Avslutt med en **Topp-anbefaling**: hvilken du ville tatt først og hvorfor.

Bruk **`docs/glossary.md`-vokabular for domenet** og arkitekturvokabularet over for strukturen. Heter konseptet "Sykmelding-inntak" i glossaret, snakk om "Sykmelding-inntak-modulen" — ikke "SykmeldingHandler" og ikke "Sykmelding-servicen".

**ADR-konflikt:** hvis en kandidat motsier en eksisterende ADR, ta den kun opp når friksjonen er ekte nok til å forsvare å gjenåpne valget. Merk det tydelig i kortet (gul callout: _"motsier ADR-0007 — men verdt å gjenåpne fordi…"_). Ikke list opp enhver teoretisk refaktorering en ADR forbyr.

Se [HTML-REPORT.md](HTML-REPORT.md) for fullt HTML-stillas, diagrammønstre og stilguide.

**Ikke** foreslå konkrete grensesnitt ennå. Etter at fila er skrevet, spør brukeren: "Hvilken av disse vil du utforske?"

### 3. Grilling-løkke

Når brukeren har valgt en kandidat, kjør `/grilling` for å gå ned
beslutningstreet sammen med dem — begrensninger, avhengigheter, formen på den
fordypede modulen, hva som ligger bak sømmen, hvilke tester som overlever. Dette
er @grillmester fase 1–2.

Når avklarte begreper eller kvalifiserende, varige beslutninger bør skrives til
`docs/`, anbefal `/grill-with-docs`, forklar hvorfor og vent på brukerens valg.
Før dokumentert løp er valgt, behold resultatene i samtalen og den aktive
oppgaven. Bruk oppgavelokal `.grill/` bare når den kallende arbeidsflyten
eksplisitt har valgt det.

Etter at dokumentert løp er valgt, skjer dokumentasjon **løpende** mens
beslutninger faller på plass:

- **Navngir du en fordypet modul etter et konsept som ikke står i `docs/glossary.md`?** Legg termen til der (bruk `/domain-modeling`). Opprett fila lazy hvis den mangler.
- **Skjerper du en uklar term underveis?** Oppdater `docs/glossary.md` med en gang.
- **Forkaster brukeren kandidaten av en bærende grunn?** Vurder ADR bare når
  valget er vanskelig å reversere, overraskende uten kontekst og resultatet av
  en reell avveining. Hopp over flyktige ("ikke verdt det nå") og
  selvinnlysende grunner. Bruk `/architecture-review` hvis NAV-spesifikke
  konsekvenser må vurderes, og `/domain-modeling` for selve ADR-en.
- **Vil du utforske alternative grensesnitt for den fordypede modulen?** Design to genuint ulike alternativer sekvensielt inline før du sammenligner dem. Bruk subagenter bare til kompakt, read-only divergent utforsking, aldri til parallell skriving.

### 4. Koble til faseløkka

Når den valgte fordypningen er gjennomgrillet:

- Skriv task-scope til issue/plan. Etter valgt dokumentert løp skriver
  `/domain-modeling` nye begreper og kvalifiserende beslutninger; vedlikeholdt
  detalj går til relevant topic-dokument.
- Bryt fordypningen ned i en trygg, inkrementell refaktoreringsplan i den aktive
  oppgaven (evt. videre til `/to-issues` for plukkbare snitt).
- Definer hva som beviser at fordypningen lyktes (tester gjennom ett
  grensesnitt, søm bekreftet av to adaptere), og returner det til den kallende
  arbeidsflyten.
