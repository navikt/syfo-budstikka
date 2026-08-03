# GDPR og personvern — NAV-signal

Generisk GDPR-teori (behandlingsgrunnlag, right-to-be-forgotten, anonymisering vs. pseudonymisering, retention-job-mønstre, samtykkehistorikk) er utenfor scope. Denne referansen dekker NAV-spesifikk kategorisering og pekere til autoritative kilder.

## NAV-spesifikk kategorisering av persondata

NAV opererer med fire PII-nivåer definert i SKILL.md-tabellen: **strengt fortrolig**, **fortrolig**, **intern**, **åpen**. Viktige NAV-spesifikke presiseringer:

- **Sykefravær kan røpe helseopplysninger.** Sykmeldinger, diagnoser og opplysninger om at en bruker er sykmeldt må klassifiseres etter gjeldende domenegrunnlag og policy.
- **Ytelsesdata er klassifiseringsbare implisitt.** "Bruker mottar AAP" eller "uføretrygd" er implisitt helseinformasjon → strengt fortrolig. Avklar alltid per ytelse.
- **Kode 6/7** (adressesperre/fortrolig adresse) må håndteres som strengt fortrolig uansett felt.
- **Fødselsnummer og D-nummer** er fortrolige. Bruk aldri ekte fnr i kode,
  eksempler eller tester. Bruk `<SYNTHETIC_FNR>` i maler og dokumentasjon. Når
  kjørbar kode krever gyldig format, bruk Skatteetatens syntetiske testserie og
  merk testdataene eksplisitt.

## Pekere til autoritative kilder

- **DPIA-prosess**: Se `references/nav-threat-model.md`. DPIA kreves ved ny behandling av personopplysninger eller vesentlig endring.
- **Auditlogg-format**: Hvis repoets dokumenterte policy etablerer CEF/ArcSight, se `references/nav-threat-model.md` for et generelt format. Repoets beslutning eier om mekanismen skal brukes.
- **Retention-policy**: Dokumenteres per behandling med hjemmel. Koordiner med sikkerhetschampion og verifiser at også testdata, eksportfiler, backup, Kafka-topics og analytics-uttrekk dekkes av policyen.
- **Datatilsynet / tilsynshenvendelser**: Eskaler til sikkerhetschampion umiddelbart. Ikke svar direkte.

## Dataminimering i praksis

Ved gjennomgang: spør om hvert PII-felt i en datamodell er nødvendig for formålet. Nye felter krever oppdatert behandlingsgrunnlag, ikke bare en Flyway-migrasjon — se `/flyway-migration` når en migrering legger til eller endrer PII-kolonner.
