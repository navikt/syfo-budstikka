package no.nav.budstikka.application.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Tag

class ArbeidsgiverNotificationPortPrivacyTest :
    FunSpec({
        val employee = PersonIdentifier("00000000000")
        val leader = PersonIdentifier("00000000000")
        val emailAddress = "sensitive@example.test"
        val emailTitle = "Sensitive title"
        val emailText = "Sensitive email text"
        val smsText = "Sensitive SMS text"
        val notificationText = "Sensitive notification text"
        val link = "https://nav.no/sensitive-link"

        val altinnExternalVarsling = AltinnExternalVarsling(emailTitle, emailText, smsText)
        val leaderExternalVarsling =
            NarmesteLederExternalVarsling(
                emailTitle,
                emailText,
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
                    AltinnResourceId.DIALOGMOETE,
                    altinnExternalVarsling,
                ),
                leaderRecipient,
                NarmesteLederRelasjon(leader, listOf(emailAddress)),
                ArbeidsgiverNotificationCloseRequest(
                    eksternId = "external-id",
                    tag = Tag.DIALOGMOETE,
                    meldingstype = ArbeidsgiverMeldingstype.BESKJED,
                ),
                ArbeidsgiverNotificationRequest(
                    virksomhetsnummer = "123456789",
                    eksternId = "external-id",
                    grupperingsid = "grouping-id",
                    tag = Tag.DIALOGMOETE,
                    tekst = notificationText,
                    lenke = link,
                    recipient = leaderRecipient,
                    meldingstype = ArbeidsgiverMeldingstype.BESKJED,
                ),
            )
        val sensitiveValues =
            listOf(
                employee.value,
                leader.value,
                emailAddress,
                emailTitle,
                emailText,
                smsText,
                notificationText,
                link,
                "123456789",
                "external-id",
                "grouping-id",
            )

        values.forEach { value ->
            test("${value::class.simpleName} omits identifiers and notification content") {
                sensitiveValues.forEach { sensitiveValue ->
                    value.toString() shouldNotContain sensitiveValue
                }
            }
        }
    })
