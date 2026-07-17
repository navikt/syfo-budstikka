package no.nav.budstikka.testsupport

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode

/**
 * Prosjekt-vid Kotest-motorkonfig: kjører specer og tester konkurrent. Trygt fordi hver
 * [no.nav.budstikka.infrastructure.database.PostgresTestFixture] er isolert i sitt eget schema
 * (B60), så samtidige specer ikke deler rader. Bor i `testsupport` (tverrgående test-infrastruktur),
 * ikke i et onion-lag som `application` — dette er test-mekanikk, ikke use-case-orkestrering.
 * Kotest oppdager `AbstractProjectConfig` via classpath-scanning, så pakken er funksjonelt fri.
 */
class BudstikkaTestConfiguration : AbstractProjectConfig() {
    override val testExecutionMode = TestExecutionMode.Concurrent
    override val specExecutionMode = SpecExecutionMode.Concurrent
}
