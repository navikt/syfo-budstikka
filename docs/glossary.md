# Glossary — syfo-budstikka

syfo-budstikka is a **domeneblind** router that dispatches something to a
recipient through the correct channel without knowing its domain, such as
dialogmøte or aktivitetskrav. The terms below are the canonical domain
vocabulary. Use them in code, contracts, issues, and documentation.

## Actors

**Produsent**:
The domain system asking Budstikka to send a dispatch. It owns *what* and
*when*, and knows the domain.
_Avoid_: avsender, klient

**Recipient (`recipient`)**:
The target of a dispatch: a sykmeldt, nærmeste leder, or arbeidsgiver.
_Avoid_: mottaker (legacy term)

**Sykmeldt**:
The person whose sickness absence is being followed up. Identified by
personident.
_Avoid_: bruker, pasient, arbeidstaker

**Nærmeste leder**:
The employer representative who follows up the sykmeldt. Budstikka resolves
this person at send time; the producer never supplies the manager identity.
_Avoid_: leder

**Arbeidsgiver**:
The organizational subunit employing the sykmeldt. Identified by orgnummer.
_Avoid_: bedrift, org

## Core concepts

**Dispatch**:
What a producer asks Budstikka to deliver to a recipient. It covers every form,
including varsel, brev, email, and a microfrontend surface, regardless of
channel.
_Avoid_: varselbestilling, bestilling, melding

**Domeneblind**:
Budstikka never branches on a domain type; it routes on channel and technical
attributes. See `adr/0001-domeneblind-varselruter.md`.

**Channel (`channel`)**:
The concrete path by which a dispatch reaches a recipient: Min side
brukervarsel, Dine Sykmeldte, Ditt Sykefravær, arbeidsgivervarsel, brev, or
microfrontend.
_Avoid_: kanal (legacy term in code)

**Varsel**:
A dispatch notifying a recipient about something, either a beskjed or an
oppgave. It is one of several dispatch forms.
_Avoid_: notifikasjon

**Microfrontend**:
An embedded surface that Budstikka enables or disables on a recipient's Min
side. It is not a varsel, but on/off visibility without recipient closure.

**Brev**:
Physical or digital mail. It cannot be completed or recalled.

**Ekstern varsling**:
An SMS or email reminder in addition to the surface.

**Reservasjon**:
The routing condition `kanVarsles == false`: the recipient cannot receive a
digital notification because they have reserved against digital contact or
lack a verified contact channel. It suppresses only ekstern varsling and may
trigger BrevFallback; it does not hide the varsel from the surface.

## Correlation and closure

**`eventId`**:
The producer's unique technical id for one message. It provides deduplication,
idempotency, and correlation for one event flow in logs. Its sole authoritative
location is the Kafka header `DispatchHeader.EVENT_ID`; it is absent from the
`Dispatch` payload. Unlike `reference`, an `eventId` is unique to one event,
while `reference` connects several events (create → Ferdigstill). See
`docs/adr/0008-hydrert-inbox-parse-ved-ingest.md`.
_Avoid_: meldings-id, korrelasjons-id (the code and log field is `eventId`)

**Reference (`reference`)**:
The producer's correlation component connecting a created dispatch to later
closure. Budstikka does not know its domain meaning, but `reference` is not
sufficient for matching by itself; closure uses the complete match key below.
_Avoid_: referanse (legacy term)

**Match key (`match key`)**:
The key used to match an inactivate operation to an earlier create operation.
The model uses `(reference, recipient_id, channel)`.
_Avoid_: matchnøkkel (legacy term in code)

**Recipient match-id (`recipient_id`)**:
The recipient identifier in `delivery` that participates in the match key,
either personident or orgnummer. For inactivate events, this is the same id the
consumer knows at create time.

**Create (`operation=CREATE`)**:
Request that Budstikka create a new dispatch.
_Avoid_: opprett (legacy term in code)

**Ferdigstill**:
Close an earlier dispatch for the recipient. The operation is
channel-specific.

**Inactivate (`operation=INACTIVATE`)**:
Budstikka's closure of a dispatch on one channel, derived from the stored create
row.
_Avoid_: inaktiver (legacy term in code)

## Delivery

**CAS (compare-and-set)**:
An atomic update pattern in which a row changes only while it has the expected
state, for example `... WHERE state='CLAIMED'`. It prevents duplicate
processing when workers compete for the same row.

**Delivery (`delivery`)**:
One dispatch to one recipient on one channel, executed and tracked by Budstikka
until terminal.
_Avoid_: leveranse (legacy term)

**SendingWindow**:
The period in which a dispatch is actually sent: continuously, or within NKS
opening hours.

**DeathGate**:
The check that stops a dispatch to a person registered as dead.

**BrevFallback**:
Send a brev when the recipient cannot be notified digitally.
