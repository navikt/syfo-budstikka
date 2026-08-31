# Leveranse av Budstikka-dashboardet

[`dashboards/syfo-budstikka.json`](dashboards/syfo-budstikka.json) er den
autoritative dashboard-spesifikasjonen. `provisioning/` brukes av den lokale
Grafana-testen. Repoet har foreløpig ingen workflow som publiserer JSON-filen til
`grafana.nav.cloud.nais.io`; en appdeploy oppdaterer derfor ikke live-dashboardet.

## Verifisering i PR

```sh
./gradlew build --no-daemon
./gradlew e2eTest --tests no.nav.budstikka.e2e.GrafanaDashboardE2ESpec --no-daemon
```

Kontrakttesten validerer blant annet PromQL, nøytral `No observations`-tilstand,
panelplassering uten overlapp og at beskrivelser ikke fremstiller tellere som
autoritativ backlogg eller regnskap. E2E-testen laster samme JSON i lokal Grafana.

## Oppdater eksisterende live-dashboard

1. Deploy appen først. Bekreft i Explore at nye metrikker finnes i dev og prod,
   eller at fravær er den forventede nøytrale tomtilstanden.
2. Åpne det eksisterende `syfo-budstikka`-dashboardet og eksporter en V2-backup.
3. Velg **Edit** og deretter **Edit as code**. Erstatt dashboard-spesifikasjonen
   med innholdet i `dashboards/syfo-budstikka.json`, valider og lagre med PR- og
   commitreferanse i endringsbeskrivelsen.
4. Oppdater det eksisterende dashboardet i stedet for å importere en ny kopi.
   Da beholdes UID, mappe, lenker og versjonshistorikk.
5. Verifiser begge miljøvalg, alle datakilder, panelenes tomtilstand og minst én
   kjent serie. For dead letters skal bare de fire faste `reason`-verdiene kunne
   forekomme.

Ved feil rulles dashboardet tilbake fra Grafanas versjonshistorikk eller den
eksporterte backupen. Appdeployen trenger normalt ikke rulles tilbake fordi
dashboardet bare leser de nye, additive metrikkene.
