package no.nav.budstikka.application.delivery

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Saktype
import no.nav.budstikka.domain.decision.Channel
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Budstikka-owned cases in Arbeidsgivernotifikasjoner. The producer only chooses a [Saktype]; this
 * puts content in that type's case for the recipient and creates the case when needed. Nothing is
 * stored: the case key is derived on every delivery, and every delivery extends the case so it is
 * deleted four months after the latest content. A key whose case has been deleted can never be used
 * again downstream, so the next generation of the key is used instead.
 */
class Arbeidsgiversaker(
    private val publisher: ArbeidsgiversakPublisher,
    private val personnavn: PersonnavnLookup,
    private val config: ArbeidsgiversakConfig,
    private val clock: Clock = Clock.System,
) {
    /**
     * Makes sure the [saktype] case exists for [mottaker] and lives at least four months past
     * [activityAt] (or now, if later). Extending first doubles as the existence check, so an
     * existing case costs one call; only a missing case is created, with its title looked up.
     */
    suspend fun ensure(
        channel: Channel,
        saktype: Saktype,
        virksomhetsnummer: String,
        mottaker: Saksmottaker,
        correlationId: String,
        activityAt: Instant? = null,
    ): SakResolution {
        val base = saktype.name + ":" + mottaker.keyPart(virksomhetsnummer)
        val merkelapp = saktype.merkelapp
        val keepUntil = maxOf(clock.now(), activityAt ?: clock.now()).plus(RETENTION_MONTHS, DateTimeUnit.MONTH, OSLO)

        suspend fun extend(grupperingsid: String) =
            withChannelHandlerFailureContext(channel, "extending case") {
                publisher.extendCase(ArbeidsgiversakExtendRequest(grupperingsid, merkelapp, keepUntil, correlationId))
            }
        var tittel: String? = null
        var generation = 1
        var knownMissing = false
        while (generation <= MAX_GENERATIONS) {
            val grupperingsid = key(base, generation)
            if (!knownMissing) {
                when (val extended = extend(grupperingsid)) {
                    ArbeidsgiverNotificationResponse.Published -> return SakResolution.Ready(grupperingsid, merkelapp)
                    is ArbeidsgiverNotificationResponse.Rejected -> return SakResolution.Failed(DeliveryOutcome.Failed(extended.reason))
                    else -> Unit
                }
            }
            // A later generation exists only when this one is deleted; finding it skips the lookup and nySak.
            if (generation < MAX_GENERATIONS) {
                val next = key(base, generation + 1)
                when (val extended = extend(next)) {
                    ArbeidsgiverNotificationResponse.Published -> return SakResolution.Ready(next, merkelapp)
                    is ArbeidsgiverNotificationResponse.Rejected -> return SakResolution.Failed(DeliveryOutcome.Failed(extended.reason))
                    else -> Unit
                }
            }
            val title =
                tittel
                    ?: saktype
                        .tittel(withChannelHandlerFailureContext(channel, "looking up name") { personnavn.findName(mottaker.sykmeldt) })
                        .also { tittel = it }
            val created =
                withChannelHandlerFailureContext(channel, "creating case") {
                    publisher.createCase(
                        ArbeidsgiversakCreateRequest(
                            virksomhetsnummer = virksomhetsnummer,
                            grupperingsid = grupperingsid,
                            tag = merkelapp,
                            tittel = title,
                            lenke = mottaker.link(),
                            keepUntil = keepUntil,
                            recipient = mottaker.recipient(),
                            correlationId = correlationId,
                        ),
                    )
                }
            when (created) {
                // Fager answers a concurrent creation of the same case as a replay and ignores its
                // deletion time, so the case is extended once more.
                ArbeidsgiverNotificationResponse.Published -> {
                    extend(grupperingsid)
                    return SakResolution.Ready(grupperingsid, merkelapp)
                }
                is ArbeidsgiverNotificationResponse.CaseDeleted -> {
                    generation++
                    knownMissing = true
                }
                is ArbeidsgiverNotificationResponse.Rejected -> return SakResolution.Failed(DeliveryOutcome.Failed(created.reason))
                is ArbeidsgiverNotificationResponse.NotYetAvailable ->
                    throw ChannelHandlerFailure(channel, "creating case", IllegalStateException(created.reason))
            }
        }
        return SakResolution.Failed(DeliveryOutcome.Failed("${channel.name} case key has $MAX_GENERATIONS deleted generations"))
    }

    /** Sets the status a producer asked for on [sak], after its content; `null` when it is set. */
    suspend fun setStatus(
        channel: Channel,
        sak: SakResolution.Ready,
        status: Saksstatus,
        correlationId: String,
    ): DeliveryOutcome.Failed? {
        val response =
            withChannelHandlerFailureContext(channel, "setting case status") {
                publisher.setCaseStatus(ArbeidsgiversakStatusRequest(sak.grupperingsid, sak.merkelapp, status, correlationId))
            }
        return when (val outcome = response.toDeliveryOutcome(channel)) {
            DeliveryOutcome.Sent -> null
            is DeliveryOutcome.Failed -> outcome
        }
    }

    private fun key(
        base: String,
        generation: Int,
    ) = if (generation == 1) base else "$base:$generation"

    private fun Saksmottaker.keyPart(virksomhetsnummer: String): String =
        when (this) {
            is Saksmottaker.NarmesteLeder -> relasjonId.toString()
            is Saksmottaker.AltinnRessurs -> "$ressurs:${pseudonym(virksomhetsnummer, sykmeldt)}"
        }

    /** Stable and opaque: the same organisation and Sykmeldt always give the same value, which reveals neither. */
    private fun pseudonym(
        virksomhetsnummer: String,
        sykmeldt: PersonIdentifier,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(config.pseudonymNokkel.toByteArray(), HMAC_ALGORITHM))
        return mac
            .doFinal("$virksomhetsnummer:${sykmeldt.value}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(PSEUDONYM_LENGTH)
    }

    private fun Saksmottaker.link(): String? =
        when (this) {
            is Saksmottaker.NarmesteLeder -> "${config.dineSykmeldteUrl.trimEnd('/')}/$relasjonId"
            is Saksmottaker.AltinnRessurs -> null
        }

    private fun Saksmottaker.recipient(): ArbeidsgiverNotificationRecipient =
        when (this) {
            is Saksmottaker.NarmesteLeder -> ArbeidsgiverNotificationRecipient.NarmesteLeder(narmesteLederFnr, sykmeldt)
            is Saksmottaker.AltinnRessurs -> ArbeidsgiverNotificationRecipient.AltinnRessurs(ressurs)
        }
}

/**
 * @property dineSykmeldteUrl base of the leader's page for a Sykmeldt in Dine Sykmeldte; the leader
 *   relation's id is appended.
 * @property pseudonymNokkel secret key for the case key of an Altinn resource case. Changing it starts
 *   new cases for every organisation and Sykmeldt.
 */
data class ArbeidsgiversakConfig(
    val dineSykmeldteUrl: String,
    val pseudonymNokkel: String,
) {
    override fun toString(): String = "ArbeidsgiversakConfig(dineSykmeldteUrl=$dineSykmeldteUrl)"
}

/** Who a case is for; decides its key, link and downstream recipient. */
sealed interface Saksmottaker {
    val sykmeldt: PersonIdentifier

    /** The case of one leader relation; a new leader gets a case of their own. */
    data class NarmesteLeder(
        val relasjonId: UUID,
        val narmesteLederFnr: PersonIdentifier,
        override val sykmeldt: PersonIdentifier,
    ) : Saksmottaker {
        override fun toString(): String = "NarmesteLeder()"
    }

    /** The case everyone with [ressurs] at the organisation sees about [sykmeldt]. */
    data class AltinnRessurs(
        val ressurs: String,
        override val sykmeldt: PersonIdentifier,
    ) : Saksmottaker {
        override fun toString(): String = "AltinnRessurs()"
    }
}

sealed interface SakResolution {
    data class Ready(
        val grupperingsid: String,
        val merkelapp: String,
    ) : SakResolution {
        override fun toString(): String = "Ready(merkelapp=$merkelapp)"
    }

    data class Failed(
        val outcome: DeliveryOutcome.Failed,
    ) : SakResolution
}

/** The registered merkelapp of a case type. */
internal val Saktype.merkelapp: String
    get() =
        when (this) {
            Saktype.DIALOGMOTE -> "Dialogmøte"
            Saktype.OPPFOLGINGSPLAN -> "Oppfølging"
        }

/** The case title shown to the employer; Arbeidsgivernotifikasjoner rejects titles above 140 characters. */
internal fun Saktype.tittel(navn: Personnavn?): String {
    val fulltNavn = navn?.fulltNavn
    return when (this) {
        Saktype.DIALOGMOTE -> fulltNavn?.let { "Dialogmøte med $it" } ?: "Dialogmøte"
        Saktype.OPPFOLGINGSPLAN -> fulltNavn?.let { "Oppfølgingsplan for $it" } ?: "Oppfølgingsplan"
    }.take(MAX_CASE_TITLE_LENGTH)
}

private const val MAX_CASE_TITLE_LENGTH = 140
private const val RETENTION_MONTHS = 4
private const val MAX_GENERATIONS = 20
private const val PSEUDONYM_LENGTH = 32
private const val HMAC_ALGORITHM = "HmacSHA256"
private val OSLO = TimeZone.of("Europe/Oslo")
