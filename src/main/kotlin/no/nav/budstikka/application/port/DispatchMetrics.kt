package no.nav.budstikka.application.port

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

/**
 * Måleport (issue #28) for beslutnings- og leveranse-workerne. Application-laget emitterer
 * domenehendelser gjennom denne porten; en Micrometer-adapter i infrastructure teller dem opp på det
 * delte Prometheus-registeret. Slik holder workerne seg fri for Micrometer-import (samme
 * port/adapter-søm som [TransactionRunner] og repositoriene, ADR 0007).
 *
 * Kontrakt: implementasjonene teller kun; de kaster aldri og gjør ingen I/O, slik at en målefeil
 * aldri kan velte en effektuering. Labels holdes lav-kardinale og PII-frie ([Channel]-navn og
 * faste utfall) — aldri fnr, event-id eller andre personopplysninger. [inboxDropped] tar en [DropReason]
 * (ikke fri streng) nettopp for å håndheve lav kardinalitet i selve port-kontrakten.
 */
interface DispatchMetrics {
    fun inboxClaimed(count: Int)

    fun inboxEmptyPoll()

    fun inboxProcessed()

    fun inboxDropped(reason: DropReason)

    fun inboxFailed()

    fun deliveryClaimed(count: Int)

    fun deliveryEmptyPoll()

    fun deliverySent(channel: Channel)

    fun deliveryFailed(channel: Channel)
}

/** Ingen-op måleport for tester og løp uten registrering. */
object NoDispatchMetrics : DispatchMetrics {
    override fun inboxClaimed(count: Int) = Unit

    override fun inboxEmptyPoll() = Unit

    override fun inboxProcessed() = Unit

    override fun inboxDropped(reason: DropReason) = Unit

    override fun inboxFailed() = Unit

    override fun deliveryClaimed(count: Int) = Unit

    override fun deliveryEmptyPoll() = Unit

    override fun deliverySent(channel: Channel) = Unit

    override fun deliveryFailed(channel: Channel) = Unit
}
