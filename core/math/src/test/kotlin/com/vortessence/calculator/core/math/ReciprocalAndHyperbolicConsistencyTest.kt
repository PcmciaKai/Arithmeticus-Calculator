package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Every new function against Kotlin's own math, and its symbolic derivative against a difference quotient. */
class ReciprocalAndHyperbolicConsistencyTest {

    private class Case(val name: String, val reference: (Double) -> Double, val points: List<Double>, val isInverse: Boolean = false)

    private val cases = listOf(
        Case("sec", { 1 / cos(it) }, listOf(-1.2, -0.4, 0.3, 0.9, 2.0, 4.0)),
        Case("csc", { 1 / sin(it) }, listOf(-2.0, -0.5, 0.4, 1.3, 2.8, 5.0)),
        Case("cot", { cos(it) / sin(it) }, listOf(-2.0, -0.5, 0.4, 1.3, 2.8, 5.0)),
        Case("asec", { acos(1 / it) }, listOf(-5.0, -1.5, 1.2, 2.0, 7.0), true),
        Case("acsc", { asin(1 / it) }, listOf(-5.0, -1.5, 1.2, 2.0, 7.0), true),
        Case("acot", { atan2(1.0, it) }, listOf(-5.0, -0.7, 0.3, 1.0, 4.0), true),
        Case("sinh", { sinh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("cosh", { cosh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("tanh", { tanh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("sech", { 1 / cosh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("csch", { 1 / sinh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("coth", { 1 / tanh(it) }, listOf(-3.0, -0.5, 0.2, 1.0, 2.5)),
        Case("asinh", { asinh(it) }, listOf(-4.0, -0.5, 0.3, 1.0, 6.0), true),
        Case("acosh", { acosh(it) }, listOf(1.1, 1.5, 2.0, 5.0), true),
        Case("atanh", { atanh(it) }, listOf(-0.9, -0.3, 0.1, 0.5, 0.95), true),
        Case("asech", { acosh(1 / it) }, listOf(0.1, 0.4, 0.8, 0.99), true),
        Case("acsch", { asinh(1 / it) }, listOf(-3.0, -0.5, 0.3, 1.0, 4.0), true),
        Case("acoth", { atanh(1 / it) }, listOf(-4.0, -1.5, 1.2, 2.0, 5.0), true)
    )

    private fun number(value: Double) = if (value < 0) listOf(OperatorNode(MathOp.MINUS), NumberNode((-value).toString())) else listOf(NumberNode(value.toString()))

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    private fun calc(input: MathString, mode: CalculationMode = CalculationMode.NUMERIC, angle: AngleUnit = AngleUnit.RADIANS) = runBlocking {
        MathEngine.calculate(input, MathContext(mode = mode, angleUnit = angle))
    }

    private fun isTrigonometric(name: String) = !name.endsWith("h")

    @Test
    fun valuesAgreeWithKotlinInRadians() {
        for (case in cases) for (x in case.points) {
            for (mode in CalculationMode.entries.filter { it == CalculationMode.NUMERIC || it == CalculationMode.EXACT }) {
                val res = calc(ms(FunctionNode(case.name, MathString(number(x)))), mode)
                assertTrue("${case.name}($x) $mode: ${res.issues}", res.isSuccess)
                val expected = case.reference(x)
                assertEquals("${case.name}($x) $mode", expected, res.numericValue, 1e-9 * max(1.0, abs(expected)))
            }
        }
    }

    @Test
    fun trigonometricValuesAgreeWithKotlinInDegrees() {
        for (case in cases.filter { isTrigonometric(it.name) }) for (x in case.points) {
            val res = calc(ms(FunctionNode(case.name, MathString(number(x)))), angle = AngleUnit.DEGREES)
            assertTrue("${case.name}($x): ${res.issues}", res.isSuccess)
            // The operand is in degrees, the result of an inverse function too
            val expected = if (case.isInverse) Math.toDegrees(case.reference(x)) else case.reference(Math.toRadians(x))
            assertEquals("${case.name}($x)°", expected, res.numericValue, 1e-8 * max(1.0, abs(expected)))
        }
    }

    @Test
    fun symbolicDerivativesAgreeWithDifferenceQuotients() {
        for (angle in AngleUnit.entries) for (case in cases) {
            if (angle == AngleUnit.DEGREES && !isTrigonometric(case.name)) continue
            for (x in case.points) {
                val derivative = ms(DerivativeNode(ms(FunctionNode(case.name, ms(VariableNode("x")))), MathString(number(x))))
                val res = calc(derivative, angle = angle)
                assertTrue("d/dx ${case.name} at $x $angle: ${res.issues}", res.isSuccess)
                // The same function as the calculator works it out: the angle in degrees on the way in or out
                val f = { t: Double ->
                    when {
                        angle != AngleUnit.DEGREES || !isTrigonometric(case.name) -> case.reference(t)
                        case.isInverse -> Math.toDegrees(case.reference(t))
                        else -> case.reference(Math.toRadians(t))
                    }
                }
                val h = 1e-5 * max(1.0, abs(x))
                val expected = (f(x + h) - f(x - h)) / (2 * h)
                assertEquals("d/dx ${case.name} at $x $angle", expected, res.numericValue, 1e-5 * max(1.0, abs(expected)))
            }
        }
    }

    @Test
    fun inverseFunctionsUndoTheirFunctions() {
        val pairs = listOf("sinh" to "asinh", "tanh" to "atanh", "sech" to "asech", "csch" to "acsch", "coth" to "acoth", "sec" to "asec", "csc" to "acsc")
        for ((function, inverse) in pairs) {
            // Positive operands, where each of these is one to one
            for (x in listOf(0.3, 0.8, 1.4)) {
                val inner = FunctionNode(function, MathString(number(x)))
                val res = calc(ms(FunctionNode(inverse, ms(inner))))
                if (res.issues.contains(MathIssue.TRIGONOMETRIC_DOMAIN)) continue
                assertTrue("$inverse($function($x)): ${res.issues}", res.isSuccess)
                assertEquals("$inverse($function($x))", x, res.numericValue, 1e-9)
            }
        }
    }

    @Test
    fun hyperbolicFunctionsAreDefinedClosetoZeroAndInfinity() {
        // csch and coth are huge but defined next to 0; only 0 itself is a pole
        val csch = calc(ms(FunctionNode("csch", ms(NumberNode("1E-12")))))
        assertTrue(csch.issues.toString(), csch.isSuccess)
        assertEquals(1e12, csch.numericValue, 1e3)
        val tiny = calc(ms(FunctionNode("csch", ms(NumberNode("1E-16")))))
        assertTrue(tiny.issues.toString(), tiny.isSuccess)
        assertEquals(1e16, tiny.numericValue, 1e6)
        assertEquals(1e16, calc(ms(FunctionNode("coth", ms(NumberNode("1E-16"))))).numericValue, 1e6)
        assertTrue(calc(ms(FunctionNode("csch", ms(NumberNode("0"))))).issues.contains(MathIssue.DIVISION_BY_ZERO))
        // Small results are not rounded to 0
        assertEquals(1e-15, calc(ms(FunctionNode("sinh", ms(NumberNode("1E-15"))))).numericValue, 1e-25)
        assertEquals(1e-20, calc(ms(FunctionNode("asinh", ms(NumberNode("1E-20"))))).numericValue, 1e-30)
        // sinh overflows long before the largest double
        assertTrue(calc(ms(FunctionNode("cosh", ms(NumberNode("800"))))).issues.contains(MathIssue.VALUES_TOO_LARGE))
        // sech and tanh do not
        assertEquals(0.0, calc(ms(FunctionNode("sech", ms(NumberNode("800"))))).numericValue, 1e-300)
        assertEquals(1.0, calc(ms(FunctionNode("tanh", ms(NumberNode("800"))))).numericValue, 1e-12)
    }

    @Test
    fun equationsThatApproachZeroHaveNoSpuriousRoots() {
        fun solve(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }
        // e^(−x), sech x and cosh x − sinh x are tiny for large x, but never 0
        for (text in listOf("e^{-x}=0", "sech(x)=0", "cosh(x)=sinh(x)", "2*cosh(x)=2*sinh(x)", "csch(x)=0")) {
            assertTrue(text, solve(text).issues.contains(MathIssue.NO_REAL_SOLUTION))
        }
        // …while the ones that do have roots still find them
        assertEquals("x = 0.6931471806", solve("cosh(x)=sinh(x)+0.5").result.toString().trim())
        assertEquals("x = 0", solve("sinh(x)=x").result.toString().trim())
    }
}
