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
    fun theStepsGoTheWayOfATextbook() {
        val solution = steps("taylor(sin(x),x,0,5)")!!
        assertEquals(
            listOf(StepRule.TAYLOR_PROBLEM, StepRule.TAYLOR_FORMULA, StepRule.TAYLOR_DERIVATIVES, StepRule.TAYLOR_VALUES, StepRule.TAYLOR_SERIES, StepRule.TAYLOR_SIMPLIFY),
            solution.steps.mapNotNull { it.rule }
        )
        fun line(rule: StepRule) = solution.steps.first { it.rule == rule }
        fun shown(rule: StepRule) = line(rule).expression.toString()
        // What is asked for: f, a and n
        assertEquals("[f(x) = sin(x)]", shown(StepRule.TAYLOR_PROBLEM).trim('{', '}'))
        assertEquals(listOf("a = 0", "n = 5"), line(StepRule.TAYLOR_PROBLEM).notes.map { it.toString() })
        // The formula for n = 5, the fraction before the power
        assertTrue(shown(StepRule.TAYLOR_FORMULA), shown(StepRule.TAYLOR_FORMULA).contains("T₅(x) = f(0) + f′(0) × x + (f″(0))/(2!) × (x)^(2)"))
        assertTrue(shown(StepRule.TAYLOR_FORMULA), shown(StepRule.TAYLOR_FORMULA).contains("(f⁽⁵⁾(0))/(5!) × (x)^(5)"))
        // f to f⁽⁵⁾, one in each line, and their values with the point put in
        assertTrue(shown(StepRule.TAYLOR_DERIVATIVES).contains("f‴(x) =  − cos(x)"))
        assertTrue(shown(StepRule.TAYLOR_VALUES).contains("f‴(0) =  − cos([0]) =  − 1"))
        // The values put in, also those that are 0, then the factorials worked out
        assertTrue(shown(StepRule.TAYLOR_SERIES), shown(StepRule.TAYLOR_SERIES).contains("([0])/(2!)"))
        assertEquals(listOf("2! = 2", "3! = 6", "4! = 24", "5! = 120"), line(StepRule.TAYLOR_SIMPLIFY).notes.map { it.toString() })
        assertEquals("x − ((x)^(3))/(6) + ((x)^(5))/(120)", solution.result.toString())
        // A point put in stays as it is: 3·1
        assertTrue(steps("taylor(x^2+3x,x,1,0)")!!.steps.first { it.rule == StepRule.TAYLOR_VALUES }.expression.toString().contains("3 × [1]"))
        // A point that is not a whole number is named a
        assertEquals("a = π", steps("taylor(sin(x),x,pi,3)")!!.steps[1].notes.first().toString())
        // Nothing to show for a constant
        assertNull(steps("taylor(5,3)"))
        assertNull(steps("taylor(1/x,x,0,2)"))
    }

    @Test
    fun aDefinitionOfTheVariableIsNotUsed() {
        // x := 3 is a value for x elsewhere, but taylor(f, x, …) is about x as a variable
        val defined = MathContext(variables = mapOf("x" to TestInput.parse("3")))
        val result = runBlocking { MathEngine.calculate(TestInput.parse("taylor(sin(x),x,0,5)"), defined) }
        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals(shown("taylor(sin(x),x,0,5)"), result.result.toString())
        assertNotNull(runBlocking { MathEngine.steps(TestInput.parse("taylor(sin(x),x,0,5)"), defined) })
        // Another letter that is defined is used
        val other = MathContext(variables = mapOf("y" to TestInput.parse("2")))
        assertEquals("2x − ((x)^(3))/(3)", runBlocking { MathEngine.calculate(TestInput.parse("taylor(sin(x)y,x,0,3)"), other) }.result.toString())
        // And so is a defined point
        val point = MathContext(variables = mapOf("a" to TestInput.parse("1")))
        assertEquals(shown("taylor(ln(x),x,1,3)"), runBlocking { MathEngine.calculate(TestInput.parse("taylor(ln(x),x,a,3)"), point) }.result.toString())
        // The same for apart
        assertEquals(shown("apart(1/(x^2-1))"), runBlocking { MathEngine.calculate(TestInput.parse("apart(1/(x^2-1),x)"), defined) }.result.toString())
    }

    @Test
    fun modesAndDisplay() {
        // Numeric mode gives decimals; the answer that is reused is not rounded to the places shown
        val numeric = runBlocking { MathEngine.calculate(TestInput.parse("taylor(e^x,x,1,3)"), MathContext(mode = com.vortessence.calculator.core.math.context.CalculationMode.NUMERIC, decimalPlaces = 2)) }
        assertTrue(numeric.result.toString(), numeric.result.toString().startsWith("2.72 + 2.72(x − 1.00)"))
        assertTrue(numeric.answer.toString(), numeric.answer.toString().startsWith("2.718281828459045"))
        // In degrees the variable is in degrees: sin(x°) ≈ x·π/180
        val degrees = runBlocking { MathEngine.calculate(TestInput.parse("taylor(sin(x),x,0,1)"), MathContext(angleUnit = com.vortessence.calculator.core.math.context.AngleUnit.DEGREES)) }
        assertEquals("x × (π)/(180)", degrees.result.toString())
        // Symbolic coefficients, and a function whose second derivative is not known
        assertEquals("1 + ax + (x)^(2) × ((a)^(2))/(2)", shown("taylor(e^{a*x},x,0,2)"))
        assertEquals(listOf(MathIssue.CANT_DIFFERENTIATE), issues("taylor(gamma(x),x,1,2)"))
        // The result is reused as typed: the answer is the same polynomial
        val first = calc("taylor(sin(x),x,0,5)")
        assertEquals(first.result.toString(), first.answer.toString())
        // Equations and inequalities with the polynomial
        assertEquals("x =  − 1 + sqrt(5), x =  − 1 − sqrt(5)", shown("taylor(e^x,x,0,2)=3"))
    }

    @Test
    fun aSlowExpansionStopsAtTheTimeLimit() {
        // sin(x)^x at order 12 takes seconds; with a short limit it is an error, not a partial series
        val start = System.currentTimeMillis()
        val result = runBlocking { MathEngine.calculate(TestInput.parse("taylor(sin(x)^x,x,1,12)"), MathContext(), timeoutMillis = 300) }
        assertEquals(listOf(MathIssue.TIMEOUT), result.issues)
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 3000)
        // The steps give up the same way
        assertNull(runBlocking { MathEngine.steps(TestInput.parse("taylor(sin(x)^x,x,1,12)"), MathContext(), timeoutMillis = 300) })
        // The large but quick ones are fine
        assertTrue(calc("taylor(e^{e^{e^x}},x,0,12)").isSuccess)
    }
}
