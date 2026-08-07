package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.domain.decision.DeathGate
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.decision.ReservationGate
import no.nav.budstikka.domain.decision.SendingWindowGate
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.domain.foundation.ReservationLookup

fun DependencyRegistry.gateModule() {
    // A dead recipient must be dropped before reservation can add a reserve Brev.
    provide<List<DecisionRule>> {
        listOf(
            SendingWindowGate(),
            DeathGate(resolve<DeathLookup>()),
            ReservationGate(resolve<ReservationLookup>()),
        )
    }
}
