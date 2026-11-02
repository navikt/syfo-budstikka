package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Optional
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.infrastructure.client.fager.generated.type.AltinnRessursMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.FutureTemporalInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NaermesteLederMottakerInput
import kotlin.time.Instant

private const val HARD_DELETE_AFTER_FOUR_MONTHS = "P4M"
private val OSLO_TIME_ZONE = TimeZone.of("Europe/Oslo")

internal fun ArbeidsgiverNotificationRecipient.toGraphqlRecipient() =
    when (this) {
        is ArbeidsgiverNotificationRecipient.AltinnRessurs ->
            MottakerInput(
                altinnRessurs = Optional.present(AltinnRessursMottakerInput(resource)),
            )
        is ArbeidsgiverNotificationRecipient.NarmesteLeder ->
            MottakerInput(
                naermesteLeder =
                    Optional.present(
                        NaermesteLederMottakerInput(
                            naermesteLederFnr = narmesteLederFnr.value,
                            ansattFnr = ansattFnr.value,
                        ),
                    ),
            )
    }

internal fun Instant?.toHardDeleteInput(): FutureTemporalInput =
    this?.let {
        FutureTemporalInput(den = Optional.present(LocalDateTime.Formats.ISO.format(it.toLocalDateTime(OSLO_TIME_ZONE))))
    } ?: FutureTemporalInput(om = Optional.present(HARD_DELETE_AFTER_FOUR_MONTHS))

internal fun <T : Any> T?.toOptional(): Optional<T?> = if (this == null) Optional.Absent else Optional.present(this)

internal fun <T : Any> List<T>?.toOptional(): Optional<List<T>> = if (this == null) Optional.Absent else Optional.present(this)

internal fun rejected(resultType: String) =
    ArbeidsgiverNotificationResponse.Rejected(
        "Arbeidsgiver notification API rejected request: $resultType",
    )
