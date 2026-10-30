package no.nav.budstikka.application.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import kotlin.time.Instant

class ArbeidsgiverNotificationPortPrivacyTest :
    FunSpec({
        val employee = PersonIdentifier("00000000000")
        val leader = PersonIdentifier("00000000000")
        val emailAddress = "sensitive@example.test"
        val emailTitle = "Sensitive title"
        val emailHtmlBody = "<p>Sensitive email body</p>"
        val smsText = "Sensitive SMS text"
        val notificationText = "Sensitive notification text"
        val link = "https://nav.no/sensitive-link"
        val visibleUntil = Instant.parse("2026-07-01T10:00:00Z")
        val caseTitle = "Sensitive case title"
        val address = "Sensitive address"

        val altinnExternalVarsling = AltinnExternalVarsling(emailTitle, emailHtmlBody, smsText)
        val leaderExternalVarsling =
            NarmesteLederExternalVarsling(
                emailTitle,
                emailHtmlBody,
                listOf(emailAddress),
            )
        val leaderRecipient =
            ArbeidsgiverNotificationRecipient.NarmesteLeder(
                narmesteLederFnr = leader,
                ansattFnr = employee,
                externalVarsling = leaderExternalVarsling,
            )
        val values =
            listOf(
                altinnExternalVarsling,
                leaderExternalVarsling,
                ArbeidsgiverNotificationRecipient.AltinnRessurs(
                    "sensitive-resource",
                    altinnExternalVarsling,
                ),
                leaderRecipient,
                NarmesteLederRelasjon(leader, listOf(emailAddress), TEST_LEDERRELASJON_ID),
                ArbeidsgiverNotificationRequest(
                    virksomhetsnummer = "123456789",
                    eksternId = "external-id",
                    grupperingsid = "grouping-id",
                    tag = "sensitive-tag",
                    tekst = notificationText,
                    lenke = link,
                    recipient = leaderRecipient,
                    meldingstype = ArbeidsgiverMeldingstype.BESKJED,
                    visibleUntil = visibleUntil,
                ),
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = "123456789",
                    grupperingsid = "grouping-id",
                    tag = "sensitive-tag",
                    tittel = caseTitle,
                    lenke = link,
                    keepUntil = visibleUntil,
                    recipient = leaderRecipient,
                    correlationId = "external-id",
                ),
                ArbeidsgiversakExtendRequest("grouping-id", "sensitive-tag", visibleUntil, "external-id"),
                ArbeidsgiversakStatusRequest("grouping-id", "sensitive-tag", Saksstatus.FERDIG, "external-id"),
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = "123456789",
                    grupperingsid = "grouping-id",
                    tag = "sensitive-tag",
                    eksternId = "external-id",
                    tekst = notificationText,
                    lenke = link,
                    startAt = visibleUntil,
                    endAt = visibleUntil,
                    lokasjon = Kalenderavtalelokasjon(address, "0001", address),
                    erDigitalt = false,
                    tilstand = Kalenderavtaletilstand.AVLYST,
                    recipient = leaderRecipient,
                    correlationId = "external-id",
                ),
                KalenderavtaleUpdateRequest("sensitive-tag", "external-id", null, notificationText, leaderExternalVarsling, "external-id"),
                SakResolution.Ready("grouping-id", "Dialogmøte"),
                Saksmottaker.NarmesteLeder(TEST_LEDERRELASJON_ID, leader, employee),
                Saksmottaker.AltinnRessurs("sensitive-resource", employee, "sensitive-case-key"),
                KalenderavtaleUpdateResponse.Updated("grouping-id"),
                Personnavn(caseTitle, null, caseTitle),
            )
        val sensitiveValues =
            listOf(
                employee.value,
                leader.value,
                emailAddress,
                emailTitle,
                emailHtmlBody,
                smsText,
                notificationText,
                link,
                "123456789",
                "external-id",
                "grouping-id",
                "sensitive-resource",
                "sensitive-tag",
                visibleUntil.toString(),
                caseTitle,
                address,
                TEST_LEDERRELASJON_ID.toString(),
                "sensitive-case-key",
            )

        values.forEach { value ->
            test("${value::class.simpleName} omits identifiers and notification content") {
                sensitiveValues.forEach { sensitiveValue ->
                    value.toString() shouldNotContain sensitiveValue
                }
            }
        }
    })
