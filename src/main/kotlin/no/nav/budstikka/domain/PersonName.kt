package no.nav.budstikka.domain

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
