package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.contract.Budstikka

/**
 * The topic these consumer tests build records on is derived from the contract, never retyped: a
 * second literal here would let a test keep passing on a topic no Produsent publishes to. That the
 * shipped `application.conf` defaults to this same topic is proven by
 * [no.nav.budstikka.infrastructure.kafka.config.BudstikkaTopicConfigTest].
 */
internal const val TOPIC: String = Budstikka.TOPIC
