package no.nav.budstikka.bootstrap

import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.infrastructure.LivenessCheck
import no.nav.budstikka.infrastructure.kafka.consumer.ConsumerRunner
import no.nav.budstikka.infrastructure.task.BackgroundLoop

/**
 * The pod's is_alive probe is green only while every background loop is still cycling: the Kafka
 * consumer runners and the [BackgroundLoop] workers. Ktor DI allows a single binding per type, so the
 * probe is aggregated here at the composition root — the only place that sees both loop families.
 * See docs/HELSESJEKK.md; this must never depend on broker availability, lag or processing success.
 */
fun DependencyRegistry.livenessModule() {
    provide<LivenessCheck> {
        val runners = resolve<List<ConsumerRunner<*, *>>>()
        val tasks = resolve<List<BackgroundLoop>>()
        LivenessCheck { runners.all { it.isAlive() } && tasks.all { it.isAlive() } }
    }
}
