package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.PersonIdentifier

/** Looks up a person's name at send time, for a case title; budstikka never stores it. */
interface PersonnavnLookup {
    /** The registered name, or `null` when the register has none. */
    suspend fun findName(ident: PersonIdentifier): Personnavn?
}

data class Personnavn(
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
) {
    /** The name as shown to people, with each part capitalised: «Anne-Marie Nordmann». */
    val fulltNavn: String
        get() = listOfNotNull(fornavn, mellomnavn?.takeUnless { it.isBlank() }, etternavn).joinToString(" ") { it.capitalised() }

    override fun toString(): String = "Personnavn()"
}

private fun String.capitalised(): String =
    trim().lowercase().split(" ").joinToString(" ") { word ->
        word.split("-").joinToString("-") { part -> part.replaceFirstChar(Char::uppercaseChar) }
    }
