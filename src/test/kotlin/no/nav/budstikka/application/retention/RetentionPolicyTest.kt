package no.nav.budstikka.application.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

class RetentionPolicyTest :
    FunSpec({
        test("rejects an absolute inbox ceiling shorter than ordinary inbox retention") {
            shouldThrow<IllegalArgumentException> {
                RetentionPolicy(inboxAndDeadLetterRetention = 100.days, inboxAbsoluteRetention = 99.days)
            }.message shouldBe "inboxAbsoluteRetention must be at least inboxAndDeadLetterRetention"
        }

        test("allows the absolute inbox ceiling to equal ordinary inbox retention") {
            RetentionPolicy(inboxAndDeadLetterRetention = 100.days, inboxAbsoluteRetention = 100.days)
                .inboxAbsoluteRetention shouldBe 100.days
        }
    })
