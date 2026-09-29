package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Regression tests for issues found during code review. */
class MathEngineRegressionTest {

    private fun n(s: String) = NumberNode(s)
    private fun v(name: String) = VariableNode(name)
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun fn(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)

    private fun calc(
        input: MathString,
        mode: CalculationMode = CalculationMode.EXACT,
        angle: AngleUnit = AngleUnit.RADIANS
    ): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(mode = mode, angleUnit = angle))
    }

    private fun assertSuccess(res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues} for '${res.result}'", res.isSuccess)
    }

    /** k * pi / 12 */
    private fun piTwelfths(k: Int) = frac(ms(n("$k"), ConstantNode("π")), ms(n("12")))

    @Test
    fun exactTrigTablesMatchNumericValuesForAllMultiplesOfPiOver12() {
        for (k in 0 until 24) {
            val x = k * PI / 12
            val s = calc(ms(fn("sin", piTwelfths(k))))
            assertSuccess(s)
            assertEquals("sin(${k}π/12)", sin(x), s.numericValue, 1e-12)
            val c = calc(ms(fn("cos", piTwelfths(k))))
            assertSuccess(c)
            assertEquals("cos(${k}π/12)", cos(x), c.numericValue, 1e-12)
            if (k % 12 != 6) {
                val t = calc(ms(fn("tan", piTwelfths(k))))
                assertSuccess(t)
                assertEquals("tan(${k}π/12)", tan(x), t.numericValue, 1e-9)
            } else {
                assertTrue(calc(ms(fn("tan", piTwelfths(k)))).issues.contains(MathIssue.DIVISION_BY_ZERO))
            }
        }
    }

    @Test
    fun numericModeKeepsTrigOfVariablesSymbolic() {
        val res = calc(ms(fn("sin", v("x"))), CalculationMode.NUMERIC)
        assertSuccess(res)
        assertEquals("sin(x)", res.result.toString())
    }

    @Test
    fun degreesAreConvertedExactlyOnce() {
        val res = calc(ms(fn("sin", n("1")), op(MathOp.PLUS), n("1")), angle = AngleUnit.DEGREES)
        assertSuccess(res)
        assertEquals(1.0 + sin(PI / 180), res.numericValue, 1e-12)

        val once = TrigonometricFunction(TrigType.SIN, Value(1L)).canonicalize(MathContext(angleUnit = AngleUnit.DEGREES))
        val twice = once.canonicalize(MathContext(angleUnit = AngleUnit.DEGREES))
        assertEquals(once, twice)
    }

    @Test
    fun inverseTrigHonorsAngleUnitAndGivesExactValues() {
        val half = frac(ms(n("1")), ms(n("2")))
        val deg = calc(ms(fn("asin", half)), angle = AngleUnit.DEGREES)
        assertSuccess(deg)
        assertEquals("30", deg.result.toString())

        val rad = calc(ms(fn("asin", half)))
        assertSuccess(rad)
        assertEquals(PI / 6, rad.numericValue, 1e-12)

        val degNumeric = calc(ms(fn("atan", n("2"))), CalculationMode.NUMERIC, AngleUnit.DEGREES)
        assertEquals(Math.toDegrees(kotlin.math.atan(2.0)), degNumeric.numericValue, 1e-9)

        val symbolic = calc(ms(fn("acos", n("0.3"))), angle = AngleUnit.DEGREES)
        assertEquals(Math.toDegrees(kotlin.math.acos(0.3)), symbolic.numericValue, 1e-9)

        assertTrue(calc(ms(fn("asin", n("2")))).issues.contains(MathIssue.TRIGONOMETRIC_DOMAIN))
    }

    @Test
    fun subtractionOfProductsIsDisplayedWithASingleMinus() {
        assertEquals("x − 2y", calc(ms(v("x"), op(MathOp.MINUS), n("2"), v("y"))).result.toString())
        assertEquals("−3", calc(ms(n("2"), op(MathOp.MINUS), n("5"))).result.toString().replace(" ", ""))
        assertEquals("x − y", calc(ms(v("x"), op(MathOp.MINUS), v("y"))).result.toString())
    }

    @Test
    fun sumsInsideProductsAreParenthesized() {
        val res = calc(ms(v("x"), v("y"), ParenthesesNode(ms(v("a"), op(MathOp.PLUS), v("b")))))
        assertSuccess(res)
        assertEquals("xy(a + b)", res.result.toString())
    }

    @Test
    fun integralsAreNotSnappedToFakeFractions() {
        val eToX = ExponentNode(ms(ConstantNode("e")), ms(v("x")))
        val res = calc(ms(IntegralNode(ms(n("0")), ms(n("1")), ms(eToX))))
        assertSuccess(res)
        assertEquals(Math.E - 1, res.numericValue, 1e-9)
        assertTrue(res.result.nodes.none { it is FractionNode })

        val sinIntegral = calc(ms(IntegralNode(ms(n("0")), ms(ConstantNode("π")), ms(fn("sin", v("x"))))), CalculationMode.NUMERIC)
        assertSuccess(sinIntegral)
        assertEquals(2.0, sinIntegral.numericValue, 1e-9)
    }

    @Test
    fun scientificNumbersCanBeParsedAndResultsReused() {
        val sci = calc(ms(n("1.0000000e+20")))
        assertSuccess(sci)
        assertEquals(1e20, sci.numericValue, 1.0)

        val big = calc(ms(ExponentNode(ms(n("2")), ms(n("70")))), CalculationMode.NUMERIC)
        assertSuccess(big)
        val reused = calc(big.answer!!, CalculationMode.NUMERIC)
        assertSuccess(reused)
        assertEquals(big.numericValue, reused.numericValue, big.numericValue * 1e-9)

        val tiny = calc(ms(n("0.000000123456")), CalculationMode.NUMERIC)
        assertTrue(tiny.result.nodes.any { it is SciExponentNode })
        assertEquals(1.23456e-7, calc(tiny.answer!!).numericValue, 1e-15)
    }

    @Test
    fun numericFormattingHasNoTrailingZeros() {
        assertEquals("0.1", calc(ms(n("0.1")), CalculationMode.NUMERIC).result.toString())
        val third = calc(ms(frac(ms(n("1")), ms(n("3")))), CalculationMode.NUMERIC)
        assertEquals("0.3333333333", third.result.toString())
    }

    @Test
    fun oddRootsOfNegativeNumbers() {
        val numeric = calc(ms(RadicalNode(ms(n("3")), ms(op(MathOp.MINUS), n("8")))), CalculationMode.NUMERIC)
        assertSuccess(numeric)
        assertEquals(-2.0, numeric.numericValue, 1e-12)

        val exact = calc(ms(RadicalNode(ms(n("3")), ms(op(MathOp.MINUS), n("16")))))
        assertSuccess(exact)
        assertEquals(-Math.cbrt(16.0), exact.numericValue, 1e-12)

        assertTrue(calc(ms(RadicalNode(null, ms(op(MathOp.MINUS), n("4"))))).issues.contains(MathIssue.NEGATIVE_ROOT))

        val cubeRoot = Function1D(Power(Variable("x"), Value(1L, 3L)))
        assertEquals(-2.0, cubeRoot.evaluateAt(-8.0), 1e-12)
    }

    @Test
    fun rootsInDenominatorsAreRationalized() {
        val res = calc(ms(RadicalNode(null, ms(frac(ms(n("1")), ms(n("2")))))))
        assertSuccess(res)
        assertEquals("(sqrt(2))/(2)", res.result.toString())
        assertEquals(Math.sqrt(0.5), res.numericValue, 1e-12)
    }

    @Test
    fun invalidInputIsReportedInsteadOfSilentlyReinterpreted() {
        assertTrue(calc(ms(fn("abs", op(MathOp.MINUS), n("2")))).issues.contains(MathIssue.UNKNOWN_FUNCTION))
        assertTrue(calc(ms(n("3.5"), op(MathOp.FACTORIAL))).issues.contains(MathIssue.UNDEFINED))
        assertTrue(calc(ms(n("2"), op(MathOp.PLUS))).issues.contains(MathIssue.SYNTAX_ERROR))
        assertTrue(calc(ms(op(MathOp.MULTIPLY), n("3"))).issues.contains(MathIssue.SYNTAX_ERROR))
        assertTrue(calc(ms(frac(ms(n("1")), ms()))).issues.contains(MathIssue.SYNTAX_ERROR))
        assertTrue(calc(ms(n("1.2.3"))).issues.contains(MathIssue.SYNTAX_ERROR))
        assertTrue(calc(ms(ConstantNode("nope"))).issues.contains(MathIssue.SYNTAX_ERROR))
    }

    @Test
    fun equationSolving() {
        // x·sin(x) + 1 = 0 is not linear; it is solved numerically
        val nonLinear = calc(ms(v("x"), fn("sin", v("x")), op(MathOp.PLUS), n("1"), op(MathOp.EQUALS), n("0")))
        assertSuccess(nonLinear)
        val x0 = nonLinear.numericValue
        assertEquals(0.0, x0 * sin(x0) + 1, 1e-9)

        val noReal = calc(ms(ExponentNode(ms(v("x")), ms(n("2"))), op(MathOp.PLUS), n("1"), op(MathOp.EQUALS), n("0")))
        assertEquals(listOf(MathIssue.NO_REAL_SOLUTION), noReal.issues)

        assertEquals("true", calc(ms(n("2"), op(MathOp.PLUS), n("2"), op(MathOp.EQUALS), n("4"))).result.toString())
        assertEquals("false", calc(ms(n("2"), op(MathOp.PLUS), n("2"), op(MathOp.EQUALS), n("5"))).result.toString())
        assertEquals("true", calc(ms(v("x"), op(MathOp.PLUS), n("1"), op(MathOp.EQUALS), n("1"), op(MathOp.PLUS), v("x"))).result.toString())

        val sqrt2 = calc(ms(ExponentNode(ms(v("x")), ms(n("2"))), op(MathOp.EQUALS), n("2")))
        assertSuccess(sqrt2)
        assertEquals(2, sqrt2.result.nodes.count { it is OperatorNode && it.op == MathOp.EQUALS })
    }

    @Test
    fun exactLogarithms() {
        assertEquals("2", calc(ms(fn("log", n("100")))).result.toString())
        assertEquals("3", calc(ms(LogBaseNode(ms(n("2")), ms(n("8"))))).result.toString())
        assertEquals("(1)/(2)", calc(ms(LogBaseNode(ms(n("4")), ms(n("2"))))).result.toString())
        val symbolic = calc(ms(LogBaseNode(ms(n("2")), ms(n("5")))))
        assertTrue(symbolic.result.nodes.first() is LogBaseNode)
        assertEquals(kotlin.math.ln(5.0) / kotlin.math.ln(2.0), symbolic.numericValue, 1e-12)
    }

    @Test
    fun temperatureConversionUsesOffsets() {
        val celsius = Multiplications.of(Value(20L), UnitRegistry.CELSIUS)
        val fahrenheit = celsius.convertToUnit(UnitRegistry.FAHRENHEIT, includeUnitInResult = false, context = MathContext())
        assertEquals(Value(68L), fahrenheit)
        val kelvin = Multiplications.of(Value.ZERO, UnitRegistry.CELSIUS).canonicalize()
            .convertToUnit(UnitRegistry.K, includeUnitInResult = false, context = MathContext())
        assertEquals(273.15, kelvin!!.evaluateNumerically(), 1e-12)

        // 20 °C − 50 °F (= 10 °C) is a difference of 10 Δ°C
        val difference = calc(ms(n("20"), UnitNode("°C"), op(MathOp.MINUS), n("50"), UnitNode("°F")))
        assertSuccess(difference)
        assertEquals("10Δ°C", difference.result.toString())
    }

    @Test
    fun likeTermsAndEquality() {
        assertEquals("3x", calc(ms(v("x"), op(MathOp.PLUS), v("x"), op(MathOp.PLUS), v("x"))).result.toString())
        assertEquals("0", calc(ms(v("x"), v("y"), op(MathOp.MINUS), v("y"), v("x"))).result.toString())

        val x = Variable("x")
        assertNotEquals(Additions(x, x), Additions(x, x, x))
        assertEquals(Multiplications(x, Variable("y")), Multiplications(Variable("y"), x))
        assertEquals(Value(1L), Value(1.0))
        assertEquals(Value(1L).hashCode(), Value(1.0).hashCode())
    }

    @Test
    fun integerRootsOfHugeNumbers() {
        val tenTo60 = BigInt(BigInteger.TEN.pow(60))
        assertEquals(BigInt(BigInteger.TEN.pow(30)), BigInt.truncatedNthRoot(tenTo60, 2))
        assertEquals(BigInt(BigInteger.TEN.pow(20)), BigInt.truncatedNthRoot(tenTo60, 3))
        assertEquals(BigInt(BigInteger.TEN.pow(30)), BigInt.truncatedNthRoot(tenTo60 + BigInt(12345L), 2))
        assertEquals(BigInt(2L), BigInt.truncatedNthRoot(BigInt(26L), 3))
        assertEquals(BigInt(3L), BigInt.truncatedNthRoot(BigInt(27L), 3))
        assertEquals(BigInt(-3L), BigInt.truncatedNthRoot(BigInt(-27L), 3))
    }

    @Test(timeout = 5_000)
    fun hugePowersFailFastInsteadOfHanging() {
        val tower = ms(ExponentNode(ms(n("2")), ms(ExponentNode(ms(n("2")), ms(n("40"))))))
        assertTrue(calc(tower).issues.contains(MathIssue.VALUES_TOO_LARGE))
        val hugeExponent = ms(ExponentNode(ms(n("3")), ms(n("100000000000000000000000"))))
        assertTrue(calc(hugeExponent).issues.contains(MathIssue.VALUES_TOO_LARGE))
        assertTrue(calc(ms(n("100000"), op(MathOp.FACTORIAL))).issues.contains(MathIssue.VALUES_TOO_LARGE))
    }

    @Test(timeout = 5_000)
    fun calculationsTimeOut() = runBlocking {
        // Nested integrals whose inner bounds depend on the outer variable: ~10^9 evaluations
        val inner = IntegralNode(ms(n("0")), ms(v("y")), ms(v("z")), variable = "z")
        val middle = IntegralNode(ms(n("0")), ms(v("x")), ms(inner), variable = "y")
        val outer = IntegralNode(ms(n("0")), ms(n("1")), ms(middle), variable = "x")
        val res = MathEngine.calculate(ms(outer), MathContext(), timeoutMillis = 200)
        assertEquals("result '${res.result}'", listOf(MathIssue.TIMEOUT), res.issues)
    }

    @Test
    fun rootFinderIgnoresPolesAndInflectionPoints() {
        val x = Variable("x")
        val reciprocal = Function1D(Power(x, Value.MINUS_ONE), x)
        assertTrue(NumericRootFinder.findRoots(reciprocal, -5.0, 5.0).isEmpty())

        val tangent = Function1D(TrigonometricFunction(TrigType.TAN, x, AngleUnit.RADIANS), x)
        val roots = NumericRootFinder.findRoots(tangent, -2.0, 2.0)
        assertEquals(1, roots.size)
        assertEquals(0.0, roots[0], 1e-9)

        val cubic = Function1D(Power(x, Value(3L)), x)
        assertTrue(NumericRootFinder.findExtrema(cubic, -2.0, 2.0).isEmpty())
        assertTrue(NumericRootFinder.findExtrema(Function1D(Power(x, Value(-2L)), x), -2.0, 2.0).isEmpty())
    }

    @Test
    fun plotFunctionUsesAngleUnit() = runBlocking {
        val fn = MathEngine.createPlotFunction(ms(fn("sin", v("x"))), MathContext(angleUnit = AngleUnit.DEGREES))
        assertNotNull(fn)
        assertEquals(1.0, fn!!.evaluateAt(90.0), 1e-12)
        val data = MathEngine.analyzePlot(fn, -100.0, 100.0)
        assertTrue(data.roots.any { abs(it) < 1e-6 })
        assertTrue(data.extrema.any { abs(it.x - 90.0) < 1e-3 && abs(it.y - 1.0) < 1e-9 })
    }

    @Test
    fun scientificNotationIsPartOfTheNumber() {
        // 5×10³ = 5000 exactly, and ×10³ without a number is 10³
        assertEquals("5000", calc(ms(n("5"), SciExponentNode(ms(n("3"))))).result.toString())
        assertEquals("1000", calc(ms(SciExponentNode(ms(n("3"))))).result.toString())
        // 2 × 1.5×10⁻² = 0.03: the ×10⁻² belongs to 1.5, and negative exponents work
        val tight = calc(ms(n("2"), op(MathOp.MULTIPLY), n("1.5"), SciExponentNode(ms(op(MathOp.MINUS), n("2")))))
        assertEquals(0.03, tight.numericValue, 1e-15)
        assertTrue(calc(ms(n("5"), SciExponentNode(ms()))).issues.contains(MathIssue.SYNTAX_ERROR))

        val tokens = ms(n("1"), n("2"), SciExponentNode(ms(n("3"))), op(MathOp.PLUS), n("4"))
        assertEquals(0..2, tokens.getTokenRangeAt(0))
        assertEquals(0..2, tokens.getTokenRangeAt(2))
        assertEquals(4..4, tokens.getTokenRangeAt(4))
    }
}
