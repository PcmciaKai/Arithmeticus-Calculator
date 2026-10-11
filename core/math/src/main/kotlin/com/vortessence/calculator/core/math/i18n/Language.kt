package com.vortessence.calculator.core.math.i18n

/** The languages the texts of the math engine (error messages, notes in the steps) are available in. */
enum class Language(val tag: String) {
    ENGLISH("en"),
    GERMAN("de");

    companion object {
        /** The language for a language tag such as "de" or "de-AT"; English for any other. */
        fun fromTag(tag: String?): Language = entries.firstOrNull { tag != null && tag.lowercase().startsWith(it.tag) } ?: ENGLISH
    }
}
