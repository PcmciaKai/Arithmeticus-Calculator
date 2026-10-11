package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.i18n.Language
import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GermanTextsTest {
    private fun german() = MathContext(language = Language.GERMAN)

    @Test
    fun everyTextHasBothLanguages() {
        for (text in Text.values()) {
            assertTrue("${text.name} English is empty", text.en.isNotBlank())
            assertTrue("${text.name} German is empty", text.de.isNotBlank())
        }
    }

    @Test
    fun germanTextsKeepTheFormatPlaceholders() {
        val placeholder = Regex("%(\\d+\\$)?[sdf]")
        for (text in Text.values()) {
            assertEquals(text.name, placeholder.findAll(text.en).count(), placeholder.findAll(text.de).count())
        }
    }

    @Test
    fun anIssueIsWordedInTheRequestedLanguage() {
        assertEquals("Division by zero", MathIssue.DIVISION_BY_ZERO.message(Language.ENGLISH))
        assertEquals("Division durch null", MathIssue.DIVISION_BY_ZERO.message(Language.GERMAN))
    }

    @Test
    fun aCalculationErrorComesBackInGerman() {
        val result = runBlocking { MathEngine.calculate(TestInput.parse("1/0"), german()) }
        assertTrue(result.issues.any { it.message(Language.GERMAN) == "Division durch null" })
    }

    @Test
    fun aTruthResultIsWordedInGerman() {
        val result = runBlocking { MathEngine.calculate(TestInput.parse("2<3"), german()) }
        assertEquals(Texts.get(Text.TRUE, Language.GERMAN), result.result.toString().trim())
    }
}
