package no.nav.budstikka.testsupport

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode

class BudstikkaTestConfiguration : AbstractProjectConfig() {
    override val testExecutionMode = TestExecutionMode.Concurrent
    override val specExecutionMode = SpecExecutionMode.Concurrent
}
