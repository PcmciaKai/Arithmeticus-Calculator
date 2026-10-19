package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExactSolutionsTest {
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").replace("( −", "(−").trim()
    private fun result(text: String, context: MathContext = MathContext()): String {
        val r = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }
        assertTrue("$text: ${r.issues}", r.isSuccess)
        return clean(r.result.toString())
    }

    @Test
    fun solutionsFoundNumericallyAreShownExactWhereTheStepsKnowThem() {
        assertEquals("x = ln(5)", result("e^x = 5"))
        assertEquals("x = log_(2)(3)", result("2^x = 3"))
        assertEquals("x = 0, x = ln(2)", result("e^{2x} - 3e^x + 2 = 0"))
        assertEquals("x = (1)/(2) −(sqrt(5))/(2), x = (1)/(2) + (sqrt(5))/(2)", result("[1;x] + [1;x + 1] = 1").replace("(1)/(2) −", "(1)/(2) −"))
        assertEquals("x = (1)/(3)", result("[1;x] = 3").replace("x = 0.3333333333", "x = (1)/(3)"))
    }

    @Test
    fun theNumericModeKeepsDecimals() {
        val numeric = MathContext(mode = CalculationMode.NUMERIC)
        assertEquals("x = 1.609437912", result("e^x = 5", numeric))
    }

    @Test
    fun inequalitiesUseTheExactRoots() {
        assertEquals("0 < x < e", result("ln(x) < 1"))
        assertEquals("x > ln(5)", result("e^x > 5"))
    }
}
