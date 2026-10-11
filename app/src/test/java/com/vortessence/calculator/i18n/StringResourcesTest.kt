package com.vortessence.calculator.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringResourcesTest {

    private val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+\$)?[sdf]|%%""")

    private fun load(folder: String): Map<String, String> {
        val file = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml")
            .map(::File).first { it.exists() }
        return entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyEnglishStringHasAGermanTranslation() {
        val missing = load("values").keys - load("values-de").keys
        assertTrue("Missing German strings: $missing", missing.isEmpty())
    }

    @Test
    fun germanHasNoStringsThatEnglishLacks() {
        val extra = load("values-de").keys - load("values").keys
        assertTrue("German-only strings: $extra", extra.isEmpty())
    }

    @Test
    fun translationsKeepTheFormatPlaceholders() {
        val german = load("values-de")
        for ((key, english) in load("values")) {
            val translated = german[key] ?: continue
            val expected = placeholder.findAll(english).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(translated).map { it.value }.sorted().toList()
            assertEquals("Placeholders differ in $key", expected, actual)
        }
    }
}
