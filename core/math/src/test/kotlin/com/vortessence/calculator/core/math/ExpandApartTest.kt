package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.SeparatorNode
import com.vortessence.calculator.core.math.type.VariableNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The commands expand(e) and apart(e): the result, the issues, and the steps. */
class ExpandApartTest {

    private fun calc(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext()) }

    private fun shown(text: String): String {
        val result = calc(TestInput.parse(text))
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    private fun issues(text: String) = calc(TestInput.parse(text)).issues

    private fun rules(input: MathString): List<StepRule>? =
        runBlocking { MathEngine.steps(input, MathContext()) }?.steps?.mapNotNull { it.rule }

    /** apart(body, variable) */
    private fun apartIn(body: String, variable: String) =
        MathString(listOf(FunctionNode("apart", MathString(TestInput.parse(body).nodes + SeparatorNode + VariableNode(variable)))))

    @Test
    fun expandMultipliesOutProductsAndPowers() {
        assertEquals("(x)^(2) + 2x + 1", shown("expand((x+1)^2)"))
        assertEquals("(x)^(3) + 2(x)^(2) − x − 2", shown("expand((x+1)(x-1)(x+2))"))
        assertEquals("(x)^(3) + 3(x)^(2)y + 3x(y)^(2) + (y)^(3)", shown("expand((x+y)^3)"))
        assertEquals("2x + 6", shown("expand(2(x+3))"))
        // Numbers are their own expansion
        assertEquals("5", shown("expand(5)"))
        // Part of a calculation it is the same function
        assertEquals("2(x)^(2) + 4x + 2", shown("2*expand((x+1)^2)"))
    }

    @Test
    fun apartSplitsIntoPartialFractions() {
        assertEquals("(1)/(2(x − 1)) − (1)/(2(x + 1))", shown("apart(1/(x^2-1))"))
        // The variable can be given
        assertEquals(shown("apart(1/(x^2-1))"), calc(apartIn("1/(x^2-1)", "x")).result.toString())
    }

    @Test
    fun apartHandlesTheKindsOfFactors() {
        // Two linear factors, a repeated one, a polynomial part, a quadratic without real roots
        assertEquals("(4)/(3(x − 1)) − (1)/(3(x + 2))", shown("apart((x+3)/((x-1)(x+2)))"))
        // A/(x − 1) + B/(x − 1)² + C/(x + 1)
        assertEquals(" − (1)/(4(x − 1)) + (1)/(2((x − 1))^(2)) + (1)/(4(x + 1))", shown("apart(1/((x-1)^2(x+1)))"))
        assertEquals("x + (1)/(2(x − 1)) + (1)/(2(x + 1))", shown("apart(x^3/(x^2-1))"))
        assertEquals("x + 1 + (2)/(x − 1)", shown("apart((x^2+1)/(x-1))"))
        assertEquals("(1)/(x) − (x)/((x)^(2) + 1)", shown("apart(1/(x(x^2+1)))"))
        // A sum of fractions, a denominator that cannot be split further, a polynomial
        assertEquals("(1)/(x − 1) + (1)/(x + 1)", shown("apart(1/(x-1)+1/(x+1))"))
        assertEquals("(1)/((x)^(2) + 1)", shown("apart(1/(x^2+1))"))
        assertEquals("(x)^(2) + 1", shown("apart(x^2+1)"))
    }

    @Test
    fun apartRefusesWhatItCannotSplit() {
        // x³ + x + 1 has no rational root and is not a quadratic; a second letter would be a coefficient
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(1/(x^3+x+1))"))
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(1/(x*y))"))
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(sin(x))"))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), issues("apart()"))
    }

    @Test
    fun theStepsNameTheirRules() {
        assertEquals(listOf(StepRule.PARTIAL_FRACTIONS), rules(TestInput.parse("apart(1/(x^2-1))")))
        assertEquals(listOf(StepRule.PARTIAL_FRACTIONS), rules(TestInput.parse("apart(1/(x(x^2+1)))")))
        assertEquals(listOf(StepRule.EXPAND_POWER), rules(TestInput.parse("expand((x+1)^2)")))
        assertTrue(StepRule.EXPAND in rules(TestInput.parse("expand((x+1)(x-1)(x+2))")).orEmpty())
        // Nothing to show where the argument is already in that form
        assertNull(rules(TestInput.parse("apart(1/(x^2+1))")))
        assertNull(rules(TestInput.parse("expand(5)")))
        assertNull(rules(TestInput.parse("apart(1/(x^3+x+1))")))
    }
}
