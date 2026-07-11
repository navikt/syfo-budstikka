# 0003: Application-lag for use-case-orkestratorer; adaptere blir i infrastructure

- Status: besluttet (issue #56, beslutnings-worker)
- Dato: 2026-07-10
- Relatert: ADR 0002 (inbox-header-dedup), `docs/TEKNOLOGI.md` (Prosjektstruktur), beslutning B28 i `docs/CONTEXT.md`

## Kontekst

Issue #56 innførte bakgrunns-workere: en task-mekanisme (`BaseTask`, `Heartbeat`) og en konkret
worker (`InboxMessageTask`) som poller `inbox_message`, dekoder payload til `Dispatch` og sender
videre. Workeren havnet i et nytt `application`-lag, men den dokumenterte strukturen i
`docs/TEKNOLOGI.md` hadde bare tre lag: `domain`, `infrastructure`, `api`. `application` var altså
udokumentert, og pakken hadde bare én innbygger.

Spørsmålet var todelt: er `application` et legitimt lag her eller er det drift, og hvor hører de tre
tingene hjemme (task-mekanisme, konkret task, Kafka-handler)? En Kafka-handler
(`InboxMessageHandler`) og en task orkestrerer beslektede ting, men lever i dag i ulike lag.

## Beslutning

`application` innføres som et eksplisitt use-case-lag. Konkret:

1. **`application` er et fjerde lag** for use-case-orkestratorer. Det kan avhenge av `domain` og
   `infrastructure`-porter; ingenting innover (`domain`, `infrastructure`) peker hit.
2. **Task-mekanismen (`BaseTask`, `Heartbeat`) blir i `infrastructure/task`.** Den er plumbing:
   coroutine-løkke, heartbeat, `AutoCloseable`-livssyklus, ingen domenekunnskap.
3. **Konkrete tasks (`InboxMessageTask`) bor i `application`.** De snakker bare domene og porter
   (repository, config) og bruker mekanismen som utførelsesramme.
4. **Kafka-handleren (`InboxMessageHandler`) blir i `infrastructure/kafka/consumer`.** Den er en
   drivende adapter: signaturen tar `ConsumerRecord`, den leser Kafka-headere og styrer offset- og
   dead-letter-semantikk, og den gjør null domenearbeid (byte-eksakt lagring per ADR 0002).
5. **Plasserings-test:** navngir klassen en transport-type (`ConsumerRecord`, `ApplicationCall`,
   HTTP-request) er den en drivende adapter og hører til `infrastructure` (eller `api` for HTTP).
   Snakker den bare domene og porter, er den et use-case og hører til `application`. Ren livssyklus
   og plumbing hører til `infrastructure`.
6. **DI-wiring som rører `application` bor i `bootstrap`** (composition root), aldri i
   `infrastructure` — det holder avhengighetsretningen utover.
7. **En port innføres når det finnes en grunn**, ikke på spekulasjon: to eller flere drivere,
   domenelogikk som skal testes uten transport, eller kompleks orkestrering.

## Konsekvenser

- ➕ De kommende workerne (delivery, opprydding) har et konsistent hjem, og plasserings-testen gir en
  rask, objektiv regel for hvor nye klasser skal ligge.
- ➕ Avhengighetsretningen er håndhevbar og verifisert: bare `bootstrap` importerer `application`;
  verken `domain` eller `infrastructure` peker innover.
- ➕ Skillet mekanisme/use-case gjør begge testbare hver for seg: `BaseTask` testes uten en konkret
  task, og en task testes uten Kafka.
- ➖ Et fjerde lag øker seremonien i en liten app. Risikoen er at tynne adaptere feilaktig havner i
  `application`; plasserings-testen demmer opp for det.
- ➖ Handleren (inbox-writer) og tasken (inbox-processor) løser to halvdeler av samme
  transactional-inbox-flyt i hvert sitt lag. Det kan forvirre til man ser writer/processor-splittet:
  adapteren lander rå bytes idempotent, use-caset dekoder og beslutter.

## Alternativer vurdert

- **Legg `InboxMessageTask` i `infrastructure/task` ved siden av `BaseTask`.** Vraket: blander
  use-case-orkestrering med plumbing og skjuler at tasken snakker domene. `infrastructure` vokser til
  en sekk uten indre skille.
- **Behold tre lag, ingen `application`; tasks i `infrastructure`.** Vraket: mister det rene skillet
  mellom mekanisme og use-case, samme problem som over.
- **Ekstraher en transport-nøytral port nå så `InboxMessageHandler` kan flytte til `application`.**
  Vraket: spekulativ generalisering. Handleren har null domenelogikk og én transport, så en port +
  DTO + mapping ville flyttet nesten ingenting til `application` mot ny seremoni. Innføres først når
  et andre triggerpunkt eller reell domenelogikk dukker opp i ingest-stien.
