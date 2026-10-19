package com.vortessence.arithmeticus.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringResourcesTest {

    private val entry = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+\$)?[sdf]|%%""")

    /** The folders of the translations, one for each [LanguageChoice] other than the system's and English. */
    private val translations = listOf("values-de", "values-fr", "values-es", "values-ru", "values-zh-rCN")

    /** The strings of [folder]; a string that is not to be translated (a language name) is left out. */
    private fun load(folder: String): Map<String, String> {
        val file = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml")
            .map(::File).first { it.exists() }
        return entry.findAll(file.readText())
            .filter { "translatable=\"false\"" !in it.groupValues[2] }
            .associate { it.groupValues[1] to it.groupValues[3] }
    }

    @Test
    fun everyEnglishStringIsTranslated() {
        val english = load("values").keys
        for (folder in translations) {
            val missing = english - load(folder).keys
            assertTrue("Missing strings in $folder: $missing", missing.isEmpty())
        }
    }

    @Test
    fun translationsHaveNoStringsThatEnglishLacks() {
        val english = load("values").keys
        for (folder in translations) {
            val extra = load(folder).keys - english
            assertTrue("Strings only in $folder: $extra", extra.isEmpty())
        }
    }

    @Test
    fun translationsKeepTheFormatPlaceholders() {
        val english = load("values")
        for (folder in translations) {
            val translated = load(folder)
            for ((key, text) in english) {
                val translation = translated[key] ?: continue
                val expected = placeholder.findAll(text).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(translation).map { it.value }.sorted().toList()
                assertEquals("Placeholders differ in $key of $folder", expected, actual)
            }
        }
    }

    @Test
    fun everyLanguageChoiceHasItsLocaleInTheConfig() {
        val file = listOf("src/main/res/xml/locales_config.xml", "app/src/main/res/xml/locales_config.xml").map(::File).first { it.exists() }
        val locales = Regex("""android:name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toSet()
        for (choice in LanguageChoice.entries) choice.tag?.let { assertTrue("$it is not in locales_config.xml", it in locales) }
    }
}
