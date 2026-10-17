package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.MathParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The command taylor(f, n), taylor(f, x, n) and taylor(f, x, a, n): the series, the errors and the steps. */
class TaylorTest {

    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun shown(text: String): String {
        val result = calc(text)
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    private fun issues(text: String) = calc(text).issues

    private fun steps(text: String) = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    @Test
    fun knownSeries() {
        assertEquals("x − ((x)^(3))/(6) + ((x)^(5))/(120)", shown("taylor(sin(x),x,0,5)"))
        assertEquals("1 − ((x)^(2))/(2) + ((x)^(4))/(24)", shown("taylor(cos(x),x,0,4)"))
        assertEquals("1 + x + ((x)^(2))/(2) + ((x)^(3))/(6)", shown("taylor(e^x,x,0,3)"))
        assertEquals("x − ((x)^(2))/(2) + ((x)^(3))/(3) − ((x)^(4))/(4)", shown("taylor(ln(1+x),x,0,4)"))
        assertEquals("1 + x + (x)^(2) + (x)^(3)", shown("taylor(1/(1-x),x,0,3)"))
        assertEquals("x + ((x)^(3))/(3) + (2(x)^(5))/(15) + (17(x)^(7))/(315)", shown("taylor(tan(x),x,0,7)"))
        // The variable and the point can be left out
        assertEquals(shown("taylor(sin(x),x,0,5)"), shown("taylor(sin(x),5)"))
        assertEquals(shown("taylor(sin(x),x,0,5)"), shown("taylor(sin(x),x,5)"))
        // Orders with nothing in them
        assertEquals("0", shown("taylor(sin(x),x,0,0)"))
        assertEquals("5", shown("taylor(5,3)"))
        assertEquals("0", shown("taylor(x^20,x,0,12)"))
    }

    @Test
    fun aPointOtherThanZeroGivesPowersOfXMinusA() {
        // Powers of (x − a), not multiplied out
        assertEquals("(x − 1) − (((x − 1))^(2))/(2) + (((x − 1))^(3))/(3)", shown("taylor(ln(x),x,1,3)"))
        assertEquals("2 + (x − 4)/(4) − (((x − 4))^(2))/(64) + (((x − 4))^(3))/(512)", shown("taylor(sqrt(x),x,4,3)"))
        assertEquals("3 + 5(x − 1) + 3((x − 1))^(2) + ((x − 1))^(3)", shown("taylor(x^3+2x,x,1,3)"))
        assertEquals("1 + (x − 1) + ((x − 1))^(2) + (((x − 1))^(3))/(2)", shown("taylor(x^x,x,1,3)"))
        assertEquals(" − (x − π) + (((x − π))^(3))/(6)", shown("taylor(sin(x),x,pi,3)"))
    }

    @Test
    fun otherLettersStayInTheCoefficients() {
        assertEquals("xcos(y) − (x)^(3) × (cos(y))/(6)", shown("taylor(sin(x)cos(y),x,0,3)"))
    }

    @Test
    fun thePolynomialMatchesTheFunctionNearThePoint() {
        // Independent of the expected strings: the error of an order-n polynomial is of the order (x − a)^(n+1)
        val x = Variable("x")
        val cases = listOf(
            Triple("sin(x)", 0.0, 8), Triple("e^{sin(x)}", 0.0, 12), Triple("tan(x)", 0.0, 9), Triple("ln(x)", 1.0, 8),
            Triple("sin(x)", 1.0, 6), Triple("sqrt(1+x)", 0.0, 10), Triple("atan(x)", 0.0, 9), Triple("1/(1+x^2)", 0.0, 12),
            Triple("x^x", 1.0, 6), Triple("e^x", 2.0, 8)
        )
        for ((function, point, order) in cases) {
            val result = calc("taylor($function,x,$point,$order)")
            assertTrue("$function: ${result.issues}", result.isSuccess)
            val original = MathParser().parse(TestInput.parse(function))
            fun error(distance: Double) = Math.abs(original.evaluateNumerically(mapOf(x to point + distance)) - result.expression.evaluateNumerically(mapOf(x to point + distance)))
            val near = error(0.1)
            val nearer = error(0.05)
            assertTrue("$function at $point: error $near", near < 1e-6)
            // Halving the distance divides the error by about 2^(n+1) ≥ 2^8 (where it is above the rounding errors)
            if (near > 1e-11) assertTrue("$function at $point: $near, $nearer", nearer < near / 100)
        }
    }

    @Test
    fun theCommandIsTheSameFunctionWhereverItIsUsed() {
        val x = Variable("x")
        assertEquals("3 + 2x + (x)^(2)", shown("2*taylor(e^x,x,0,2)+1"))
        assertEquals("x + ((x)^(2))/(2) + ((x)^(3))/(6) + ((x)^(4))/(24) + C", shown("I{taylor(e^x,x,0,3)}"))
        assertEquals("1 − ((x)^(2))/(2) + ((x)^(4))/(24)", shown("D{taylor(sin(x),x,0,5)}"))
        // Graphs and tables evaluate the typed expression
        assertEquals(Math.sin(0.5), MathParser().parse(TestInput.parse("taylor(sin(x),x,0,9)")).evaluateNumerically(mapOf(x to 0.5)), 1e-9)
    }

    @Test
    fun whatHasNoSeriesIsAnError() {
        // Not defined at the point, or a derivative is not
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), issues("taylor(1/x,x,0,2)"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("taylor(ln(x),x,0,3)"))
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), issues("taylor(sqrt(x),x,0,2)"))
        // A removable singularity is not worked out: the limits of the derivatives would only be found in floating point
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), issues("taylor(sin(x)/x,x,0,4)"))
        // The order
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("taylor(sin(x),x,0,13)"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("taylor(sin(x),x,0,-1)"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("taylor(sin(x),x,0,2.5)"))
        // Two letters need one named; the point cannot depend on the variable
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), issues("taylor(x*y,3)"))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), issues("taylor(sin(x),x,x,2)"))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), issues("taylor(sin(x),2,3)"))
    }

    @Test
    fun theStepsPutTheDerivativesIntoTheFormula() {
        val solution = steps("taylor(sin(x),x,0,5)")!!
        assertEquals(listOf(StepRule.TAYLOR_SERIES, StepRule.SIMPLIFY), solution.steps.mapNotNull { it.rule })
        // f, f′ … f⁽⁵⁾ and their values at 0
        val notes = solution.steps[1].notes.map { it.toString() }
        assertEquals(12, notes.size)
        assertEquals("f(x) = sin(x)", notes.first())
        assertEquals("f‴(0) =  − 1", notes[7])
        assertTrue(notes.last(), notes.last().startsWith("f⁽⁵⁾(0)"))
        // A point that is not a whole number is named a
        assertEquals("a = π", steps("taylor(sin(x),x,pi,3)")!!.steps[1].notes.first().toString())
        // Nothing to show for a constant
        assertNull(steps("taylor(5,3)"))
        assertNull(steps("taylor(1/x,x,0,2)"))
    }
}
