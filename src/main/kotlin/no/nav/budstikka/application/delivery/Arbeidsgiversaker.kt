package no.nav.budstikka.application.delivery

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.decision.Channel
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Budstikka-owned saker in Arbeidsgivernotifikasjoner. The producer chooses a [Sakstype]; this
 * puts content in that type's sak for the recipient and creates the sak when needed.
 *
 * Nothing is stored. The sak key is derived from the sakstype and recipient on every delivery, and
 * every delivery extends the sak, so it is deleted four months after the latest content.
 */
class Arbeidsgiversaker(
    private val publisher: ArbeidsgiversakPublisher,
    private val config: ArbeidsgiversakConfig,
    private val clock: Clock = Clock.System,
) {
    /**
     * Extends the [sakstype] sak for [mottaker] to live at least four months past [activityAt] (or
     * now, if later), or creates it when missing, and returns it. Creation uses the caller's saved
     * [name] snapshot; `null` gives a general title. This service does not look up names.
     *
     * Arbeidsgivernotifikasjoner never accepts the key of a deleted sak again, so a recipient who
     * comes back after their sak was deleted gets the next generation of the key: `key`, `key:2`,
     * `key:3` and so on. The generations are tried in order: an existing sak is extended and used, a
     * missing one is created, and one that turns out to be deleted moves on to the next generation.
     */
    suspend fun extendOrCreate(
        channel: Channel,
        sakstype: Sakstype,
        virksomhetsnummer: String,
        mottaker: Saksmottaker,
        name: PersonName?,
        correlationId: String,
        activityAt: Instant? = null,
    ): SakResolution {
        val now = clock.now()
        val keepUntil = maxOf(now, activityAt ?: now).plus(RETENTION_MONTHS, DateTimeUnit.MONTH, OSLO)
        val sak = SakGenerations(channel, sakstype, virksomhetsnummer, mottaker, name, correlationId, keepUntil)
        for (generation in 1..MAX_GENERATIONS) {
            sak.extendIfExists(generation)?.let { return it }
            // A generation is only created after the one before it is deleted, so if the next one
            // exists, this one is gone. Checking that first saves a create on every
            // delivery after a deletion.
            if (generation < MAX_GENERATIONS) sak.extendIfExists(generation + 1)?.let { return it }
            sak.create(generation)?.let { return it }
        }
        return SakResolution.Failed(DeliveryOutcome.Failed("${channel.name} sak key has $MAX_GENERATIONS deleted generations"))
    }

    /** The sak destination, also used as the default notification link for this leader relation. */
    fun dineSykmeldteLink(relationId: UUID): String = "${config.dineSykmeldteUrl.trimEnd('/')}/$relationId"

    /** Sets the status a producer asked for on [sak]; `null` when it is set. */
    suspend fun setStatus(
        channel: Channel,
        sak: SakResolution.Ready,
        status: Saksstatus,
        correlationId: String,
    ): DeliveryOutcome.Failed? {
        val response =
            withChannelHandlerFailureContext(channel, "setting saksstatus") {
                publisher.setSaksstatus(ArbeidsgiversakStatusRequest(sak.grupperingsid, sak.merkelapp, status, correlationId))
            }
        return when (val outcome = response.toDeliveryOutcome(channel)) {
            DeliveryOutcome.Sent -> null
            is DeliveryOutcome.Failed -> outcome
        }
    }

    /** The generations of one sak key, and the calls [extendOrCreate] makes on them. */
    private inner class SakGenerations(
        private val channel: Channel,
        private val sakstype: Sakstype,
        private val virksomhetsnummer: String,
        private val mottaker: Saksmottaker,
        private val name: PersonName?,
        private val correlationId: String,
        private val keepUntil: Instant,
    ) {
        private val merkelapp = sakstype.merkelapp
        private val baseKey = sakstype.name + ":" + mottaker.keyPart(virksomhetsnummer)

        /** Extends the sak of [generation] and returns it; `null` when there is no such sak. */
        suspend fun extendIfExists(generation: Int): SakResolution? {
            val grupperingsid = key(generation)
            return when (val extended = extend(grupperingsid)) {
                ArbeidsgiverNotificationResponse.Published -> SakResolution.Ready(grupperingsid, merkelapp)
                is ArbeidsgiverNotificationResponse.Rejected -> SakResolution.Failed(DeliveryOutcome.Failed(extended.reason))
                is ArbeidsgiverNotificationResponse.NotYetAvailable, is ArbeidsgiverNotificationResponse.SakDeleted -> null
            }
        }

        /** Creates the sak of [generation] and returns it; `null` when the key belonged to a deleted sak. */
        suspend fun create(generation: Int): SakResolution? {
            val grupperingsid = key(generation)
            val created =
                withChannelHandlerFailureContext(channel, "creating sak") {
                    publisher.createSak(
                        ArbeidsgiversakCreateRequest(
                            virksomhetsnummer = virksomhetsnummer,
                            grupperingsid = grupperingsid,
                            tag = merkelapp,
                            title = sakstype.title(name),
                            link = mottaker.link(),
                            keepUntil = keepUntil,
                            recipient = mottaker.recipient(),
                            correlationId = correlationId,
                        ),
                    )
                }
            return when (created) {
                ArbeidsgiverNotificationResponse.Published -> {
                    // Fager answers a concurrent creation of the same sak as a replay and ignores its
                    // deletion time, so the sak is extended once more.
                    extend(grupperingsid)
                    SakResolution.Ready(grupperingsid, merkelapp)
                }
                is ArbeidsgiverNotificationResponse.SakDeleted -> null
                is ArbeidsgiverNotificationResponse.Rejected -> SakResolution.Failed(DeliveryOutcome.Failed(created.reason))
                is ArbeidsgiverNotificationResponse.NotYetAvailable ->
                    throw ChannelHandlerFailure(channel, "creating sak", IllegalStateException(created.reason))
            }
        }

        private fun key(generation: Int) = if (generation == 1) baseKey else "$baseKey:$generation"

        private suspend fun extend(grupperingsid: String) =
            withChannelHandlerFailureContext(channel, "extending sak") {
                publisher.extendSak(ArbeidsgiversakExtendRequest(grupperingsid, merkelapp, keepUntil, correlationId))
            }
    }

    private fun Saksmottaker.keyPart(virksomhetsnummer: String): String =
        when (this) {
            is Saksmottaker.NarmesteLeder -> relationId.toString()
            is Saksmottaker.AltinnRessurs -> "$resource:$virksomhetsnummer:$sakId"
        }

    private fun Saksmottaker.link(): String? =
        when (this) {
            is Saksmottaker.NarmesteLeder -> dineSykmeldteLink(relationId)
            is Saksmottaker.AltinnRessurs -> null
        }

    private fun Saksmottaker.recipient(): ArbeidsgiverNotificationRecipient =
        when (this) {
            is Saksmottaker.NarmesteLeder -> ArbeidsgiverNotificationRecipient.NarmesteLeder(narmesteLederFnr, sykmeldt)
            is Saksmottaker.AltinnRessurs -> ArbeidsgiverNotificationRecipient.AltinnRessurs(resource)
        }
}

/** @property dineSykmeldteUrl base of the leader's page for a Sykmeldt in Dine Sykmeldte; the relation id is appended. */
data class ArbeidsgiversakConfig(
    val dineSykmeldteUrl: String,
)

/** Who a sak is for; decides its key, link and downstream recipient. */
sealed interface Saksmottaker {
    val sykmeldt: PersonIdentifier

    /** The sak of one leader relation; a new leader gets a sak of their own. */
    data class NarmesteLeder(
        val relationId: UUID,
        val narmesteLederFnr: PersonIdentifier,
        override val sykmeldt: PersonIdentifier,
    ) : Saksmottaker {
        override fun toString(): String = "NarmesteLeder()"
    }

    /** The sak everyone with [resource] at the organisation sees about [sykmeldt], keyed by the producer's [sakId]. */
    data class AltinnRessurs(
        val resource: String,
        override val sykmeldt: PersonIdentifier,
        val sakId: String,
    ) : Saksmottaker {
        override fun toString(): String = "AltinnRessurs()"

        companion object {
            private val SAK_ID = Regex("[A-Za-z0-9_-]{1,64}")

            /** 1-64 letters, digits, '-' or '_', so the key has no separators. */
            fun isValidSakId(sakId: String): Boolean = sakId.matches(SAK_ID)
        }
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

/** The registered merkelapp of a sakstype. */
internal val Sakstype.merkelapp: String
    get() =
        when (this) {
            Sakstype.DIALOGMOTE -> "Dialogmøte"
            Sakstype.OPPFOLGINGSPLAN -> "Oppfølging"
        }

/** The sak title shown to the employer; Arbeidsgivernotifikasjoner rejects titles above 140 characters. */
internal fun Sakstype.title(name: PersonName?): String {
    val fullName = name?.fullName
    return when (this) {
        Sakstype.DIALOGMOTE -> fullName?.let { "Dialogmøte med $it" } ?: "Dialogmøte"
        Sakstype.OPPFOLGINGSPLAN -> fullName?.let { "Oppfølgingsplan for $it" } ?: "Oppfølgingsplan"
    }.take(MAX_SAK_TITLE_LENGTH)
}

private const val MAX_SAK_TITLE_LENGTH = 140
private const val RETENTION_MONTHS = 4
private const val MAX_GENERATIONS = 20
private val OSLO = TimeZone.of("Europe/Oslo")
