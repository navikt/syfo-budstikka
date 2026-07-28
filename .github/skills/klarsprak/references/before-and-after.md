---
description: "Concrete before-and-after examples for editing Norwegian README and user-facing backend copy, including mistranslated terms, stiff tone, and API responses. Read when editing Norwegian copy."
---
# Before and after

Examples of editing AI-heavy or stiff Norwegian README and user-facing copy for
this Ktor backend. Technical documentation, ADRs, issues, pull requests, commit
messages, logs, and operator interfaces remain English.

## AI language → get to the point

```
❌ Det er viktig å påpeke at en eventdrevet arkitektur representerer et
   betydelig skritt fremover, og spiller en avgjørende rolle i moderne
   skyinfrastruktur.

✅ Tjenesten konsumerer meldinger fra Kafka og oppdaterer databasen.
```

## Nominalisations → verbs

```
❌ Gjennomføring av en evaluering av ytelseskarakteristikkene til de ulike
   databasealternativene er nødvendig.

✅ Vi må teste ytelsen til de ulike databasene.
```

## Mistranslated technical term → keep English

```
❌ Vi må rulle tilbake avbildet og opprette en ny hemmelighet i navnerommet.

✅ Vi må gjøre rollback på imaget og opprette en ny secret i namespacet.
```

```
❌ Vi fant en grensetilfelle i utrullingsflyten som krever en hastefiks.

✅ Vi fant en edge case i deploy-flyten som krever en hotfix i prod.
```

## Anglicism → natural Norwegian

```
❌ Vi må adressere dette problemet og ta eierskap til prosessen for å
   levere en løsning som er på linje med forventningene.

✅ Vi må fikse dette. Teamet har ansvar for å finne en løsning.
```

## Overly stiff tone → collegial

```
❌ Det benyttes en hendelsesdrevet arkitektur der meldinger publiseres til
   en meldingskø for videre prosessering.

✅ Vi bruker en eventdrevet arkitektur. Meldinger publiseres til Kafka og
   plukkes opp av consumerne.
```

## API response → clear Norwegian

```
❌ call.respond(BadRequest, "Operasjonen kunne ikke gjennomføres grunnet
   manglende obligatoriske feltverdier.")

✅ call.respond(BadRequest, "Mangler fødselsdato. Send 'fodselsdato' på
   formatet ÅÅÅÅ-MM-DD.")
```

## README → get to the point

```
❌ Dette prosjektet representerer et innovativt verktøy som muliggjør effektiv
   håndtering av meldinger. Det er utviklet med tanke på å sette brukeren i sentrum.

✅ Tar imot og videreformidler meldinger i sykefraværsoppfølgingen. Bygget med
   Kotlin/Ktor, kjører på NAIS med Postgres og Kafka.
```

## Unnecessary summary → cut it

```
❌ Vi har nå gjennomgått de ulike aspektene ved migrasjonen. Som vi har sett, er
   det flere viktige hensyn å ta. Oppsummert kan man si at en vellykket migrering
   krever grundig planlegging.

✅ (Kutt hele avsnittet. Leseren har allerede lest det du oppsummerer.)
```
