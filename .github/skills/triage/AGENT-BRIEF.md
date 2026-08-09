# Writing a work-ready brief

A brief is a structured comment posted on an issue or a PR when it moves to `ready-for-agent` (or `ready-for-human`). It is the authoritative specification the work is done against. The original body and the discussion are context — the brief is the contract.

The brief states **what is to be done**: for an issue that means building the change from nothing; for a PR it means what remains *on the existing diff* — finishing it, closing gaps, addressing review points. Same principles; the PR example below shows the difference.

## Principles

### Durability over precision
The item may sit in `ready-for-agent` for days or weeks, and the codebase changes in the meantime. Write the brief so it stays useful even if files are moved or refactored.

- **Do:** describe interfaces, types, and behavior contracts. Name the concrete types, function signatures or config shapes the agent should look for or change (e.g. a `data class`, a repository method, a Kafka message schema, a Flyway DDL).
- **Do not:** reference file paths or line numbers — they go stale. Do not assume today's implementation structure survives.

### Behavior, not procedure
Describe **what** the system should do, not **how** it is implemented. The agent explores the codebase afresh and makes its own implementation choices.

- **Good:** "A Kafka record with a missing or invalid event-id header must be parked as a `dead_letter_message` row with the matching `failure_reason`, while the valid records in the same batch are still saved to the inbox."
- **Bad:** "Open `BudstikkeRoute.kt` and add an `if` at line 42."

### Complete acceptance criteria
The agent must know when it is done. Every criterion must be independently verifiable. For this Ktor repository that typically includes:

- The end-to-end behavior works (the call returns the expected response / the message is consumed idempotently / the row lands in Postgres).
- `./gradlew test` is green, incl. a new test covering the change.
- Auth in place where relevant (TokenX/Azure AD), no PII in the logs.
- NAIS config updated if the change needs a topic/accessPolicy/secret.

### Explicit scope boundaries
State what is **out of** scope. That keeps the agent from gold-plating or assuming adjacent features.

Describe task-relevant constraints in the desired behavior or in the acceptance
criteria, so the brief can be understood on its own. Follow
`docs/agents/domain.md`; do not add a general field for decision links.

## Template

```markdown
> *Generert av AI under triage.*

## Brief

**Kategori:** bug / enhancement
**Oppsummering:** én linje om hva som skal skje

**Dagens oppførsel:**
Hva skjer nå. For bugs: den ødelagte oppførselen (med reprodusert kodevei
hvis verifisert). For enhancements: status quo featuren bygger på.

**Ønsket oppførsel:**
Hva som skal skje etter at arbeidet er ferdig. Vær konkret om kant-tilfeller
og feilkontrakt.

**Sentrale grensesnitt:**
- `TypeNavn` — hva må endres og hvorfor
- `metodeNavn()` returtype — hva den returnerer nå vs hva den bør
- Config/meldingsskjema — nye felter eller former som trengs

**Akseptansekriterier:**
- [ ] Konkret, testbart kriterium 1
- [ ] `./gradlew test` grønn, inkl. ny test som dekker endringen
- [ ] Auth/PII-krav oppfylt der relevant
- [ ] NAIS-config oppdatert hvis topic/accessPolicy/secret berøres

**Utenfor scope:**
- Det som IKKE skal endres her
- Nærliggende feature som virker relatert men er separat
```

## Example — bug

```markdown
> *Generert av AI under triage.*

## Brief

**Kategori:** bug
**Oppsummering:** Kafka-konsument for budstikka-topicen dobbeltbehandler ved replay

**Dagens oppførsel:**
Når konsumenten leser samme melding på nytt (rebalansering / offset-reset),
skrives raden inn i Postgres en gang til. Reprodusert: en test som leverer
samme `ConsumerRecord` to ganger gir to rader.

**Ønsket oppførsel:**
Konsumering skal være idempotent på event-id-headeren — andre gangs behandling
av samme event-id skal være en no-op, ingen ny rad, ingen feil.

**Sentrale grensesnitt:**
- Repository-innskrivningen som persisterer hendelsen — trenger en
  idempotent insert / unik constraint på event-id
- Konsument-løkka — bør ikke kaste på allerede-sett event-id

**Akseptansekriterier:**
- [ ] Samme melding levert to ganger gir nøyaktig én rad
- [ ] `./gradlew test` grønn, inkl. ny replay-test
- [ ] Ingen PII logges ved duplikat-deteksjon

**Utenfor scope:**
- Endre meldingsskjemaet på topicen
- Idempotens for andre konsumenter
```

## Example — PR

For a PR, "Dagens oppførsel" describes the state of the diff, and the brief asks the agent to finish/fix rather than to build from scratch.

```markdown
> *Generert av AI under triage.*

## Brief

**Kategori:** enhancement
**Oppsummering:** Fullfør bidragsyters oppsummeringslogg i dead letter-replay

**Dagens oppførsel:**
PR-en legger til en oppsummeringslogg etter replay. Happy path virker, men
rader som hoppes over (fortsatt uparsebare) telles ikke med, og den nye
stien har ingen testdekning.

**Ønsket oppførsel:**
Oppsummeringen dekker både replayede og hoppede rader (`replayed_count` /
`skipped_count`), uten payload eller PII i loggen. Eksisterende
replay-semantikk — insert før delete, hoppede rader blir liggende — er urørt.

**Akseptansekriterier:**
- [ ] Både replayede og hoppede rader telles riktig i oppsummeringen
- [ ] `./gradlew test` grønn, inkl. én replay- og én skip-test
- [ ] Ingen payload eller PII i loggmeldingene

**Utenfor scope:**
- Endre hvilke rader replayen velger
- Automatisk replay uten miljøvariabel-flagget
```

## Bad brief (do not do this)

```markdown
## Brief
**Oppsummering:** Fiks triage-buggen
**Hva som skal gjøres:** Triage-greia er ødelagt. Se på hovedfila og fiks det.
Funksjonen rundt linje 150 har problemet.
**Filer å endre:** BudstikkeRoute.kt (linje 150)
```

Bad because: no category, a vague description, references to file paths/line numbers that go stale, no acceptance criteria, no scope boundaries, no current-vs-desired behavior.
