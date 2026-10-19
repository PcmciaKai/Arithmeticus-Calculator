package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.sqrt

/** Integrals with a quadratic under a root: inverse hyperbolic sine, and the logarithm where that does not fit. */
class RootIntegralTest {

    private fun calc(text: String, mode: CalculationMode = CalculationMode.EXACT) =
        runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext(mode = mode)) }

    private fun assertResult(expected: String, text: String) {
        val res = calc(text)
        assertTrue("$text: ${res.issues}", res.isSuccess)
        assertEquals(text, expected.trim().replace(Regex("\\s+"), " "), res.result.toString().trim().replace(Regex("\\s+"), " "))
    }

    private fun rules(text: String) =
        (runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) } ?: throw AssertionError("No steps for $text")).steps.mapNotNull { it.rule }

    @Test
    fun oneOverTheRootOfASquarePlusANumberIsAnInverseHyperbolicSine() {
        assertResult("asinh(x) + C", "I{[1;sqrt(x^2+1)]}")
        assertResult("asinh((x)/(2)) + C", "I{[1;sqrt(x^2+4)]}")
        assertResult("(asinh((2x)/(3)))/(2) + C", "I{[1;sqrt(4*x^2+9)]}")
        // With a term in x the square is completed: x² + 2x + 5 = (x + 1)² + 4
        assertResult("asinh((x)/(2) + (1)/(2)) + C", "I{[1;sqrt(x^2+2*x+5)]}")
        assertResult("asinh(x + 1) + C", "I{[1;sqrt((x+1)^2+1)]}")
        assertResult("asinh(1)", "I[0,1]{[1;sqrt(x^2+1)]}")
    }

    @Test
    fun oneOverTheRootOfASquareMinusANumberIsALogarithmThatHoldsOnBothSides() {
        assertResult("ln(abs(x + sqrt((x)^(2) − 1))) + C", "I{[1;sqrt(x^2-1)]}")
        assertResult("ln(abs(x + sqrt((x)^(2) − 4))) + C", "I{[1;sqrt(x^2-4)]}")
        assertResult("(ln(abs(3x + sqrt(9(x)^(2) − 1))))/(3) + C", "I{[1;sqrt(9*x^2-1)]}")
        assertResult("ln(abs(x − 1 + sqrt((x)^(2) − 2x))) + C", "I{[1;sqrt(x^2-2*x)]}")
        // Over x > 2 and over x < −2 the same antiderivative differentiates to the integrand
        val value = calc("I[2,3]{[1;sqrt(x^2-1)]}", CalculationMode.NUMERIC).numericValue
        assertEquals(ln(3 + sqrt(8.0)) - ln(2 + sqrt(3.0)), value, 1e-9)
    }

    @Test
    fun theRootOfASquarePlusOrMinusANumberToo() {
        assertResult("(xsqrt((x)^(2) + 1))/(2) + (ln(abs(x + sqrt((x)^(2) + 1))))/(2) + C", "I{sqrt(x^2+1)}")
        assertResult("(xsqrt((x)^(2) − 1))/(2) − (ln(abs(x + sqrt((x)^(2) − 1))))/(2) + C", "I{sqrt(x^2-1)}")
        assertEquals(asinh(1.0) / 2 + sqrt(2.0) / 2, calc("I[0,1]{sqrt(x^2+1)}", CalculationMode.NUMERIC).numericValue, 1e-9)
        assertEquals(0.881373587, calc("I[0,1]{[1;sqrt(x^2+1)]}", CalculationMode.NUMERIC).numericValue, 1e-9)
    }

    @Test
    fun theFormsThatWereAlreadyThereAreUnchanged() {
        assertResult("asin(x) + C", "I{[1;sqrt(1-x^2)]}")
        assertResult("atan(x) + C", "I{[1;x^2+1]}")
        assertResult("(atan((x)/(2) + (1)/(2)))/(2) + C", "I{[1;x^2+2*x+5]}")
        assertResult("(ln(abs(x + 1)))/(2) − (ln(abs(x − 1)))/(2) + C", "I{[1;1-x^2]}")
    }

    @Test
    fun stepsNameTheRuleWhereTheSquareIsXSquaredPlusOrMinusANumber() {
        assertEquals(listOf(StepRule.INTEGRAL_INVERSE_HYPERBOLIC_SINE), rules("I{[1;sqrt(x^2+1)]}"))
        assertEquals(listOf(StepRule.INTEGRAL_INVERSE_HYPERBOLIC_SINE), rules("I{[1;sqrt(x^2+4)]}"))
        assertEquals(listOf(StepRule.INTEGRAL_LOG_ROOT), rules("I{[1;sqrt(x^2-4)]}"))
        // Not a monic square: a standard integral, and no substitution u = … for the quadratic
        assertEquals(listOf(StepRule.INTEGRAL_TABLE), rules("I{[1;sqrt(4*x^2+9)]}"))
        assertEquals(listOf(StepRule.INTEGRAL_TABLE), rules("I{[1;sqrt(x^2+2*x+5)]}"))
    }
}
