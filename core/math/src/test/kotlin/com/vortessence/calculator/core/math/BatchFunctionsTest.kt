package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.numbers.SpecialMath
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** totient, modpow, tstat, tmargin, gammainc, betainc, the Lambert W function and the sine, cosine and exponential integrals. */
class BatchFunctionsTest {

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    private fun call(name: String, vararg arguments: String): MathString {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, text ->
            if (i > 0) argument.add(SeparatorNode)
            argument.addAll(MathText.fromPlainText(text)?.nodes ?: listOf(VariableNode(text)))
        }
        return ms(FunctionNode(name, MathString(argument)))
    }

    private fun calc(input: MathString, mode: CalculationMode = CalculationMode.EXACT) =
        runBlocking { MathEngine.calculate(input, MathContext(mode = mode)) }

    private fun value(name: String, vararg arguments: String, mode: CalculationMode = CalculationMode.EXACT): Double {
        val res = calc(call(name, *arguments), mode)
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun exact(name: String, vararg arguments: String): String {
        val res = calc(call(name, *arguments))
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.result.toString().replace("(", "").replace(")", "")
    }

    private fun issues(name: String, vararg arguments: String) = calc(call(name, *arguments)).issues

    private fun assertClose(expected: Double, actual: Double, relative: Double = 1e-12) =
        assertEquals(expected, actual, abs(expected) * relative)

    @Test
    fun totient() {
        assertEquals("1", exact("totient", "1"))
        assertEquals("12", exact("totient", "36"))
        assertEquals("96", exact("totient", "97"))
        assertEquals("400000000000", exact("totient", "1000000000000"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("totient", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("totient", "-5"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("totient", "2.5"))
        // A large prime cannot be factored in time
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("totient", "4611686018427387847"))
    }

    @Test
    fun modularPower() {
        assertEquals("445", exact("modpow", "4", "13", "497"))
        assertEquals("24", exact("modpow", "2", "10", "1000"))
        assertEquals("1", exact("modpow", "5", "0", "7"))
        // A negative power is the modular inverse to the power
        assertEquals("5", exact("modpow", "3", "-1", "7"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("modpow", "2", "-1", "4"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("modpow", "2", "3", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("modpow", "2", "10", "-7"))
        // The result is not negative
        assertEquals("2", exact("modpow", "-2", "3", "5"))
        // Huge powers do not need the huge number
        assertEquals("0", exact("modpow", "7", "1000000000000", "1"))
        assertEquals("1", exact("modpow", "2", "1000000006", "1000000007"))
    }

    @Test
    fun tStatisticAndMargin() {
        // mean 5.5, s² = 5/3, n = 4: t = (1/2)/√(5/12) = √(3/5)
        assertClose(0.7745966692414834, value("tstat", "5", "4", "5", "6", "7"))
        assertClose(0.7745966692414834, value("tstat", "5", "4", "5", "6", "7", mode = CalculationMode.NUMERIC))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("tstat", "5", "4"))
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), issues("tstat", "5", "4", "4", "4"))
        // tinv(0.975, 3)·√(5/12)
        assertClose(3.182446305284263 * Math.sqrt(5.0 / 12.0), value("tmargin", "0.95", "4", "5", "6", "7"), 1e-10)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tmargin", "1", "4", "5"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tmargin", "0", "4", "5"))
    }

    @Test
    fun incompleteGammaAndBeta() {
        assertClose(1 - exp(-2.0), value("gammainc", "1", "2"))
        assertClose(1 - 4 * exp(-3.0), value("gammainc", "2", "3"))
        assertClose(0.9544997361036416, value("gammainc", "0.5", "2"))
        assertEquals("0", exact("gammainc", "3", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("gammainc", "0", "1"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("gammainc", "1", "-1"))
        assertClose(0.6875, value("betainc", "0.5", "2", "3"))
        assertClose(0.3, value("betainc", "0.3", "1", "1"))
        assertEquals("0", exact("betainc", "0", "2", "3"))
        assertEquals("1", exact("betainc", "1", "2", "3"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("betainc", "1.5", "2", "3"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("betainc", "0.5", "0", "3"))
        // The chi-square distribution is the incomplete gamma function
        assertClose(value("chisqcdf", "7.5", "5"), value("gammainc", "2.5", "3.75"))
    }

    @Test
    fun lambertW() {
        assertClose(0.567143290409784, SpecialMath.lambertW(1.0))
        assertClose(1.745528002740699, SpecialMath.lambertW(10.0))
        assertClose(-0.259171101819074, SpecialMath.lambertW(-0.2))
        assertEquals(-1.0, SpecialMath.lambertW(-1.0 / Math.E), 1e-7)
        assertTrue(SpecialMath.lambertW(-0.37).isNaN())
        for (x in listOf(-0.36, -0.3, -0.1, 0.001, 0.5, 2.0, 3.0, 20.0, 1e5, 1e10, 1e100, 1e300)) {
            val w = SpecialMath.lambertW(x)
            assertTrue("W($x) = $w", w >= -1.0)
            assertEquals("W($x)", x, w * exp(w), abs(x) * 1e-13)
        }
        assertEquals("0", exact("lambertw", "0"))
        assertEquals("1", calc(ms(FunctionNode("lambertw", ms(ConstantNode("e"))))).result.toString())
        assertEquals(listOf(MathIssue.UNDEFINED), issues("lambertw", "-1"))
        assertClose(0.567143290409784, value("lambertw", "1", mode = CalculationMode.NUMERIC))
    }

    @Test
    fun trigonometricAndExponentialIntegrals() {
        assertClose(0.946083070367183, SpecialMath.sineIntegral(1.0), 1e-13)
        assertClose(1.658347594218874, SpecialMath.sineIntegral(10.0), 1e-13)
        assertClose(-1.658347594218874, SpecialMath.sineIntegral(-10.0), 1e-13)
        assertClose(0.337403922900968, SpecialMath.cosineIntegral(1.0), 1e-13)
        assertClose(-0.0454564330044554, SpecialMath.cosineIntegral(10.0), 1e-12)
        assertClose(1.895117816355937, SpecialMath.exponentialIntegral(1.0), 1e-13)
        assertClose(-0.219383934395520, SpecialMath.exponentialIntegral(-1.0), 1e-13)
        assertClose(0.454219904863173, SpecialMath.exponentialIntegral(0.5), 1e-13)
        assertClose(-0.001148295591275, SpecialMath.exponentialIntegral(-5.0), 1e-11)
        assertTrue(SpecialMath.exponentialIntegral(0.0).isNaN())
        assertTrue(SpecialMath.cosineIntegral(0.0).isNaN())
        assertTrue(SpecialMath.exponentialIntegral(800.0).isInfinite())
        // Their slopes are sin x / x, cos x / x and eˣ / x, across the points where the method changes (2 and 40)
        for (x in listOf(0.01, 0.5, 1.9, 2.1, 5.0, 17.0, 39.5, 40.5, 60.0)) {
            val h = 1e-5 * x
            fun slope(f: (Double) -> Double) = (f(x + h) - f(x - h)) / (2 * h)
            assertEquals("Si'($x)", sin(x) / x, slope(SpecialMath::sineIntegral), 1e-7)
            assertEquals("Ci'($x)", cos(x) / x, slope(SpecialMath::cosineIntegral), 1e-7)
            assertEquals("Ei'($x)", exp(x) / x, slope(SpecialMath::exponentialIntegral), 1e-6 * exp(x) / x)
        }
        for (x in listOf(-30.0, -3.0, -0.4)) {
            val h = 1e-5 * abs(x)
            assertEquals("Ei'($x)", exp(x) / x, (SpecialMath.exponentialIntegral(x + h) - SpecialMath.exponentialIntegral(x - h)) / (2 * h), 1e-7)
        }
        // Si(x) goes to π/2
        assertEquals(Math.PI / 2, SpecialMath.sineIntegral(1e6), 1e-5)
        assertEquals("0", exact("sinint", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("cosint", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("expint", "0"))
        // Ei is still finite where eˣ is not
        assertClose(Math.exp(710.0 - Math.log(710.0)), SpecialMath.exponentialIntegral(710.0) / (1 + 1 / 710.0 + 2 / (710.0 * 710.0)), 1e-3)
        assertTrue(SpecialMath.exponentialIntegral(720.0).isInfinite())
        // Limits at ±∞
        assertEquals(Math.PI / 2, calcText("sinint(inf)").numericValue, 1e-15)
        assertEquals(-Math.PI / 2, calcText("sinint(-inf)").numericValue, 1e-15)
        assertEquals(0.0, calcText("cosint(inf)").numericValue, 0.0)
        assertEquals(0.0, calcText("expint(-inf)").numericValue, 0.0)
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), calcText("expint(inf)").issues)
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), calcText("lambertw(inf)").issues)
        // Too large for a number of the calculator, also in exact mode
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("expint", "1000"))
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("gamma", "500.5"))
        assertClose(0.946083070367183, value("sinint", "1"))
        assertClose(-0.946083070367183, value("sinint", "-1"))
        assertClose(0.337403922900968, value("cosint", "-1"))
    }

    private fun calcText(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    @Test
    fun theNewFunctionsDifferentiate() {
        // The slope at x = 2 against the difference quotient of the function itself
        for ((function, f) in listOf<Pair<String, (Double) -> Double>>(
            "lambertw(x)" to SpecialMath::lambertW,
            "sinint(x)" to SpecialMath::sineIntegral,
            "cosint(x)" to SpecialMath::cosineIntegral,
            "expint(x)" to SpecialMath::exponentialIntegral,
            "lambertw(x^2)" to { x -> SpecialMath.lambertW(x * x) },
            "sinint(3*x)" to { x -> SpecialMath.sineIntegral(3 * x) }
        )) {
            val res = calcText("D{$function}")
            assertTrue("$function: ${res.issues}", res.isSuccess)
            val h = 1e-6
            val slope = res.expression.evaluateNumerically(mapOf(Variable("x") to 2.0))
            assertEquals(function, (f(2 + h) - f(2 - h)) / (2 * h), slope, 1e-6)
        }
        // W'(0) = 1
        assertEquals(1.0, calcText("D{lambertw(x)}").expression.evaluateNumerically(mapOf(Variable("x") to 0.0)), 1e-12)
    }

    @Test
    fun theIntegralsOfSineCosineAndExponentialOverX() {
        val sine = calcText("I{[sin(x);x]}")
        assertTrue("${sine.issues}", sine.isSuccess)
        assertEquals("Si(x)", sine.result.toString().let { if (it.contains("Si") || it.contains("sinint")) "Si(x)" else it })
        assertTrue(calcText("I{[cos(2*x);x]}").isSuccess)
        assertTrue(calcText("I{[e^{-x};x]}").isSuccess)
        // ∫₁² sin x / x dx = Si(2) − Si(1)
        val definite = calcText("I[1,2]{[sin(x);x]}")
        assertTrue("${definite.issues}", definite.isSuccess)
        assertEquals(SpecialMath.sineIntegral(2.0) - SpecialMath.sineIntegral(1.0), definite.numericValue, 1e-9)
        // ∫₁² eˣ/x dx = Ei(2) − Ei(1); with a factor
        val exponential = calcText("I[1,2]{[3*e^x;x]}")
        assertTrue("${exponential.issues}", exponential.isSuccess)
        assertEquals(3 * (SpecialMath.exponentialIntegral(2.0) - SpecialMath.exponentialIntegral(1.0)), exponential.numericValue, 1e-9)
        // sin x / x has no value at 0, but the integral from 0 does
        val fromZero = calcText("I[0,1]{[sin(x);x]}")
        assertTrue("${fromZero.issues}", fromZero.isSuccess)
        assertEquals(SpecialMath.sineIntegral(1.0), fromZero.numericValue, 1e-9)
        assertEquals(-SpecialMath.sineIntegral(2.0), calcText("I[2,0]{[sin(x);x]}").numericValue, 1e-9)
        // cos x / x really diverges at 0, and across it
        assertFalse(calcText("I[0,1]{[cos(x);x]}").isSuccess)
        assertFalse(calcText("I[-1,1]{[cos(x);x]}").isSuccess)
        assertFalse(calcText("I[0,1]{[1;x]}").isSuccess)
        // Not a multiple of x in the argument
        assertFalse(calcText("I{[sin(x + 1);x]}").isSuccess)
    }
}
