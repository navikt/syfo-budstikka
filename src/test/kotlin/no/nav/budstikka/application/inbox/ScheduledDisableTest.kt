package no.nav.budstikka.application.inbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.application.retention.RetentionPolicy
import java.util.UUID
import kotlin.time.Duration.Companion.days

class ScheduledDisableTest :
    FunSpec({
        test("the horizon leaves 30 days of margin before retention deletes unprocessed inbox rows") {
            SCHEDULED_DISABLE_HORIZON + 30.days shouldBe RetentionPolicy().inboxAbsoluteRetention
        }

        test("the scheduled disable event id is derived deterministically from the source event id") {
            val source = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

            scheduledDisableEventId(source) shouldBe scheduledDisableEventId(source)
            scheduledDisableEventId(source) shouldBe UUID.nameUUIDFromBytes("${source}scheduled-disable".toByteArray())
            scheduledDisableEventId(source) shouldNotBe source
        }
    })
