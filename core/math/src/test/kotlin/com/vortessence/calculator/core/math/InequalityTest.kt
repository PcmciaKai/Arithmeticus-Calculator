package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InequalityTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun result(text: String): String {
        val r = calc(text)
        assertTrue("$text: ${r.issues}", r.isSuccess)
        return r.result.toString().replace(Regex("\\s+"), " ").replace("− ", "−").trim()
    }

    @Test
    fun linear() {
        assertEquals("x < 3", result("2x + 1 < 7"))
        assertEquals("x ≥ 2", result("3x - 1 >= 5"))
        // Dividing by a negative number turns the inequality around
        assertEquals("x > −2", result("-3x < 6"))
    }

    @Test
    fun quadratic() {
        assertEquals("x < −2 or x > 2", result("x^2 - 4 > 0"))
        assertEquals("−2 < x < 2", result("x^2 < 4"))
        assertEquals("−2 ≤ x ≤ 2", result("x^2 <= 4"))
        assertEquals("x ≤ 2 or x ≥ 3", result("x^2 - 5x + 6 >= 0"))
        // The only point where it holds
        assertEquals("x = 1", result("x^2 - 2x + 1 <= 0"))
        // Everywhere except at 1
        assertEquals("x < 1 or x > 1", result("x^2 - 2x + 1 > 0"))
        assertEquals("x ∈ ℝ", result("x^2 + 1 > 0"))
        assertEquals(listOf(MathIssue.NO_REAL_SOLUTION), calc("x^2 + 1 < 0").issues)
    }

    @Test
    fun cubicAndHigher() {
        // (x − 1)(x − 2)(x − 3) > 0
        assertEquals("1 < x < 2 or x > 3", result("(x - 1)(x - 2)(x - 3) > 0"))
        assertEquals("x ≤ −1 or x ≥ 1", result("x^4 >= 1 + 0x"))
    }

    @Test
    fun rational() {
        // The zero of the numerator is in, the pole of the denominator is not
        assertEquals("x < −2 or x ≥ 1", result("[x - 1;x + 2] >= 0"))
        assertEquals("−2 < x < 3", result("[x - 3;x + 2] < 0"))
        // A hole: (x² − 1)/(x − 1) > 0 is x + 1 > 0 without x = 1
        assertEquals("−1 < x < 1 or x > 1", result("[x^2 - 1;x - 1] > 0"))
        assertEquals("x < 0 or x > 2", result("[1;x] < [1;2]"))
    }

    @Test
    fun rootsLogarithmsAndAbsoluteValues() {
        assertEquals("x ≥ 0", result("sqrt(x) >= 0"))
        assertEquals("x > 4", result("sqrt(x) > 2"))
        assertEquals("0 ≤ x < 4", result("sqrt(x) < 2"))
        assertEquals("x > 1", result("ln(x) > 0"))
        assertEquals("−2 < x < 4", result("abs(x - 1) < 3"))
        assertEquals("x < −2 or x > 4", result("abs(x - 1) > 3"))
        assertEquals("x > 0", result("e^x > 1"))
    }

    @Test
    fun withoutAVariableItIsTrueOrFalse() {
        assertEquals("true", result("3 < 5"))
        assertEquals("false", result("5 <= 3"))
        assertEquals("true", result("2 >= 2"))
    }

    @Test
    fun whatIsNotAnInequalityOfThisKind() {
        // Two variables, two periodic functions (sin(x) > 0 alone has a solution in every period, see TrigInequalityTest), two relations in a row
        assertFalse(calc("x + y > 1").isSuccess)
        assertFalse(calc("sin(x) > cos(x)").isSuccess)
        assertFalse(calc("1 < x < 3").isSuccess)
    }
}
