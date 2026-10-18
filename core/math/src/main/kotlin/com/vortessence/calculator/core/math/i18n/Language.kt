package com.vortessence.calculator.core.math.i18n

/** The languages the texts of the math engine (error messages, notes in the steps) are available in. */
enum class Language(val tag: String) {
    ENGLISH("en"),
    GERMAN("de"),
    FRENCH("fr"),
    SPANISH("es"),
    RUSSIAN("ru"),
    CHINESE("zh");

    companion object {
        /**
         * The language for a language tag such as "de", "de-AT" or "zh-Hans-CN"; English for any other. Chinese is only available
         * in simplified characters: traditional Chinese (zh-Hant, zh-TW, zh-HK, zh-MO) is English, as the resources of the app are.
         */
        fun fromTag(tag: String?): Language {
            val parts = tag?.lowercase()?.split('-', '_') ?: return ENGLISH
            if (parts[0] == CHINESE.tag && "hans" !in parts && ("hant" in parts || parts.any { it == "tw" || it == "hk" || it == "mo" })) {
                return ENGLISH
            }
            return entries.firstOrNull { it.tag == parts[0] } ?: ENGLISH
        }
    }
}
