# Vokabular og feilmodi

Full betydning av begrepene i `SKILL.md`, pluss diagnose-katalog for skills som ikke oppfører seg. Lastes på etterspørsel via kontekst-pekerne i hoved-skillen.

## Vokabular

- **Forutsigbarhet** — at Copilot tar samme _prosess_ hver kjøring, ikke at den produserer samme tekst. Rotdyden alle andre grep tjener.
- **Trigger-signal** — et tegn i situasjonen som bør få Copilot til å fyre skillen: oppgavetype, frase brukeren sier, fil/område som røres, eller `/navn`-anrop. `description` er en samling av disse, ikke en oppsummering.
- **Gren** — en distinkt situasjon eller vei gjennom skillen. Én trigger per gren; flere fraser som beskriver samme gren er duplisering.
- **Ledende ord** — kompakt begrep fra modellens forhåndstrening som forankrer en hel atferd i få tokens (_tracer bullet_, _idempotent_, _vertikalt snitt_, _grill_, _tight_, _red/green_). Forankrer utførelse i kroppen og invokasjon i description.
- **Informasjonshierarki** — stigen materiale rangeres på etter hvor umiddelbart Copilot trenger det: steg i SKILL.md → referanse i SKILL.md → ekstern referanse i `references/`.
- **Steg** — en ordnet handling i SKILL.md; den primære tieren. Ender på et fullføringskriterium.
- **Referanse** — definisjon, regel eller faktum som slås opp ved behov, ikke utføres i rekkefølge.
- **Fullføringskriterium** — betingelsen som forteller at et steg er ferdig. Skal være _sjekkbar_ (ferdig vs. ikke-ferdig) og, der det teller, _uttømmende_. Et vagt kriterium inviterer premature completion. I dette repoet er gaten ofte `./gradlew test` / `./gradlew build` med ferskt output.
- **Progressive disclosure** — å flytte tungt materiale nedover stigen, ut av SKILL.md og inn i en `references/`-fil, så toppen holder seg lesbar.
- **Kontekst-peker** — setningen i SKILL.md som sender Copilot til ekstern referanse. Ordlyden, ikke målet, avgjør hvor pålitelig materialet nås. Navngi hva fila inneholder.
- **Kontrakt** — positiv, sjekkbar spesifikasjon av hva som må holde (hus-mønsteret her, jf. `grill-with-docs`, `tdd`), foretrukket framfor en forbudsliste.
- **Granularitet** — hvor fint skills deles. Hver ny skill koster alltid-lastet `description`-kontekst; splitt bare når kuttet fortjener det.
- **Legwork** — gravingen Copilot gjør inne i arbeidet (lese kode, sjekke ADR) drevet av et krevende fullføringskriterium.

## Feilmodi

- **Premature completion** — å avslutte et steg før det reelt er ferdig, fordi oppmerksomheten glir mot _å være ferdig_. Forsvar i rekkefølge: skjerp fullføringskriteriet først (billig, lokalt); bare hvis det er uunngåelig vagt _og_ du ser hastingen, skjul de etterfølgende stegene ved å splitte sekvensen.
- **Duplisering** — samme mening flere steder. Koster vedlikehold og tokens, og blåser opp meningens tilsynelatende rang på stigen. Kollaps til én sannhetskilde.
- **Sediment** — gamle lag som blir liggende fordi å legge til føles trygt og å fjerne føles risikabelt. Default-skjebnen til enhver skill uten pruning-disiplin.
- **Sprawl** — en skill rett og slett for lang, selv om hver linje er levende og unik. Kuren er stigen: disclose referanse bak pekere, og splitt etter gren eller sekvens så hver vei bærer kun sitt.
- **No-op** — en linje modellen alt følger som default, så du betaler kontekst for å si ingenting. Testen: endrer den atferd kontra default? Et svakt ledende ord (_vær grundig_ når Copilot alt er nokså grundig) er en no-op; fiksen er et sterkere ord (_nådeløst_), ikke en annen teknikk.

## Repo-spesifikke skjevheter å skrive inn

Skills i syfo-budstikka deler en kontekst de bør bygge på i stedet for å gjenoppfinne:

- **Auth** er en typisk blind-spot — TokenX (borger), Azure AD (ansatt/M2M), NAVident-claim. Skriv det inn der det er relevant.
- **PII** — logg aldri fnr eller særlige kategorier i klartekst; bruk callId/aktørreferanse.
- **Kafka** — konsumenter skal være idempotente og tåle replay.
- **Flyway** — migreringer er append-only; endre aldri en deployet migrering.
- **NAIS** — `accessPolicy.inbound/outbound`, topic-tilgang og secrets hører i manifestet, ikke hardkodes.
- **Arkitekturbeslutninger** utløser ADR i `.grill/adr/` via `/nav-architecture-review` — en skill bør peke dit, ikke ta valget selv.
