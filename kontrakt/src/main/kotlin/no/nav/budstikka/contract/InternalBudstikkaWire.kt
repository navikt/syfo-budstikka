package no.nav.budstikka.contract

/**
 * Marks the raw wire surface of the Budstikka contract: the envelope, the canonical JSON, the header
 * names and the serialisable content DTOs — plus the variants that have no delivering channel yet.
 *
 * These types exist because budstikka itself has to read and route what a Produsent puts on the
 * topic. They are the engine room, not the front door. Reaching into them from producing code means
 * assembling topic, key, headers and payload by hand, and nothing then stops a dispatch that
 * budstikka accepts but never delivers.
 *
 * A Produsent therefore uses [Budstikka] instead, which is free of this requirement. The gate is
 * compile time only: it changes no bytes on the wire and no runtime behaviour.
 *
 * Opting in is a deliberate, reviewable act. Do it per declaration where it belongs:
 *
 * ```kotlin
 * @OptIn(InternalBudstikkaWire::class)
 * fun decode(json: String): Dispatch = dispatchJson.decodeFromString(json)
 * ```
 *
 * or, for an application that consumes the contract wholesale (as budstikka does), once in Gradle:
 *
 * ```kotlin
 * kotlin.compilerOptions.optIn.add("no.nav.budstikka.contract.InternalBudstikkaWire")
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This is budstikka's raw wire API, meant for the consumer side of the contract. " +
            "Produsenter send through the Budstikka object instead. " +
            "Opt in with @OptIn(InternalBudstikkaWire::class) only if you really are decoding or routing the wire.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class InternalBudstikkaWire
