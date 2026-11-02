package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.PersonIdentifier

/** Looks up a person's name at send time, for a sak title; budstikka never stores it. */
interface PersonNameLookup {
    /** The registered name, or `null` when the register has none. */
    suspend fun findName(ident: PersonIdentifier): PersonName?
}

data class PersonName(
    val firstName: String,
    val middleName: String?,
    val lastName: String,
) {
    /** The name as shown to people, with each part capitalised: «Anne-Marie Nordmann». */
    val fullName: String
        get() = listOfNotNull(firstName, middleName?.takeUnless { it.isBlank() }, lastName).joinToString(" ") { it.capitalised() }

    override fun toString(): String = "PersonName()"
}

private fun String.capitalised(): String =
    trim().lowercase().split(" ").joinToString(" ") { word ->
        word.split("-").joinToString("-") { part -> part.replaceFirstChar(Char::uppercaseChar) }
    }
