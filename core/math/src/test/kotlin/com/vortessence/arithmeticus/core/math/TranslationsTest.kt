package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.expressions.UnitRegistry
import com.vortessence.arithmeticus.core.math.i18n.Language
import com.vortessence.arithmeticus.core.math.i18n.Text
import com.vortessence.arithmeticus.core.math.i18n.Texts
import com.vortessence.arithmeticus.core.math.i18n.UnitNames
import org.junit.Assert.*
import org.junit.Test

/** Every language has every text and every unit name; the other languages than German are checked here. */
class TranslationsTest {
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    @Test
    fun everyTextIsInEveryLanguage() {
        for (language in Language.entries) for (text in Text.entries) {
            assertTrue("${text.name} in $language is empty", Texts.get(text, language).isNotBlank())
        }
    }

    @Test
    fun everyTranslationKeepsTheFormatPlaceholders() {
        for (language in Language.entries) for (text in Text.entries) {
            assertEquals("${text.name} in $language", placeholder.findAll(text.en).count(), placeholder.findAll(Texts.get(text, language)).count())
        }
    }

    @Test
    fun aTextWithTwoPartsCanPutThemInAnotherOrder() {
        assertEquals("当 x → 1⁺ 时，x − 1 为正", Texts.format(Text.IS_POSITIVE_FOR, "x − 1", "x → 1⁺", language = Language.CHINESE))
    }

    @Test
    fun everyUnitFamilyAndCategoryHasANameInEveryLanguage() {
        for (language in Language.entries - Language.ENGLISH) {
            for (category in UnitRegistry.categories) {
                assertTrue("category ${category.name} in $language", category.name in UnitNames.categories(language)!!)
                for (family in category.families) {
                    assertTrue("family ${family.name} in $language", family.name in UnitNames.families(language)!!)
                    for (unit in family.units) assertTrue("unit ${unit.name} in $language", unit.name in UnitNames.units(language)!!)
                }
            }
        }
    }

    @Test
    fun languageTagsAreMatched() {
        assertEquals(Language.FRENCH, Language.fromTag("fr-CA"))
        assertEquals(Language.SPANISH, Language.fromTag("es_MX"))
        assertEquals(Language.RUSSIAN, Language.fromTag("ru"))
        assertEquals(Language.CHINESE, Language.fromTag("zh-CN"))
        assertEquals(Language.CHINESE, Language.fromTag("zh-Hans-HK"))
        assertEquals(Language.ENGLISH, Language.fromTag("zh-TW"))
        assertEquals(Language.ENGLISH, Language.fromTag("zh-Hant-CN"))
        assertEquals(Language.ENGLISH, Language.fromTag("it"))
        assertEquals(Language.ENGLISH, Language.fromTag(null))
    }
}
