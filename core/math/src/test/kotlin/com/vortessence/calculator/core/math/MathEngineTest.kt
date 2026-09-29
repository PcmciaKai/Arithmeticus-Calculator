package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MathEngineTest {

    @Test
    fun testBigIntArithmetic() {
        val a = BigInt("12345678901234567890")
        val b = BigInt("98765432109876543210")
        val sum = a + b
        assertEquals("111111111011111111100", sum.toString())
        assertEquals(BigInt(120L), BigInt.factorial(5L))
        assertEquals(BigInt(10L), BigInt.binomial(5L, 2L))
    }

    @Test
    fun testExactRadicalSimplification() {
        // sqrt(18) = 18^(1/2) -> 3 * sqrt(2)
        val p = Power(Value(18L), Value.HALF)
        val simplified = p.canonicalize()
        assertTrue(simplified is Multiplications)
        val mult = simplified as Multiplications
        assertEquals(2, mult.operands.size)
        assertEquals(Value(3L), mult.operands[0])
        val radPart = mult.operands[1] as Power
        assertEquals(Value(2L), radPart.base)
        assertEquals(Value.HALF, radPart.exponent)

        // sqrt(72) -> 6 * sqrt(2)
        val p72 = Power(Value(72L), Value.HALF).canonicalize()
        assertTrue(p72 is Multiplications)
        val mult72 = p72 as Multiplications
        assertEquals(Value(6L), mult72.operands[0])
        assertEquals(Value(2L), (mult72.operands[1] as Power).base)
    }

    @Test
    fun testExactFractions() {
        // 1/2 + 1/3 = 5/6
        val r1 = Rational.of(1L, 2L)
        val r2 = Rational.of(1L, 3L)
        val sum = r1 + r2
        assertEquals(Rational.of(5L, 6L), sum)
    }

    @Test
    fun testExactTrigonometry() {
        // sin(pi/6) = 1/2
        val piOver6 = Multiplications.of(Constant.PI, Value(1L, 6L))
        val sinExpr = TrigonometricFunction(TrigType.SIN, piOver6)
        val res = sinExpr.canonicalize()
        assertEquals(Value.HALF, res)

        // cos(pi/3) = 1/2
        val piOver3 = Multiplications.of(Constant.PI, Value(1L, 3L))
        val cosExpr = TrigonometricFunction(TrigType.COS, piOver3)
        assertEquals(Value.HALF, cosExpr.canonicalize())

        // tan(pi/4) = 1
        val piOver4 = Multiplications.of(Constant.PI, Value(1L, 4L))
        val tanExpr = TrigonometricFunction(TrigType.TAN, piOver4)
        assertEquals(Value.ONE, tanExpr.canonicalize())
    }

    @Test
    fun testUnitConversion() {
        // 2 km to m -> 2000 m
        val twoKm = Multiplications.of(Value(2L), UnitRegistry.KM)
        val inMeters = twoKm.convertToUnit(UnitRegistry.M, includeUnitInResult = true)
        assertNotNull(inMeters)
        assertTrue(inMeters is Multiplications)
        val mult = inMeters as Multiplications
        assertEquals(Value(2000L), mult.operands[0])
        assertEquals(UnitRegistry.M, mult.operands[1])

        // 1 m + 50 cm -> 3/2 m
        val sumUnits = Additions(
            Multiplications.of(Value(1L), UnitRegistry.M),
            Multiplications.of(Value(50L), UnitRegistry.CM)
        ).canonicalize()
        assertTrue(sumUnits is Multiplications)
        val mSum = sumUnits as Multiplications
        assertEquals(Value(Rational.of(3L, 2L)), mSum.operands[0])
        assertEquals(UnitRegistry.M, mSum.operands[1])
    }

    @Test
    fun testLinearEquationSolving() {
        // 2x - 6 = 0 => x = 3
        val x = Variable("x")
        val eq = Equals(
            Additions(Multiplications.of(Value(2L), x), Value(-6L)),
            Value.ZERO
        )
        val sols = eq.solveForVariable(x)
        assertEquals(1, sols.size)
        assertEquals(Value(3L), sols.first())
    }

    @Test
    fun testQuadraticEquationSolving() {
        // x^2 - 5x + 6 = 0 => x = 2, 3
        val x = Variable("x")
        val eq = Equals(
            Additions(
                Power(x, Value.TWO),
                Multiplications.of(Value(-5L), x),
                Value(6L)
            ),
            Value.ZERO
        )
        val sols = eq.solveForVariable(x)
        assertEquals(2, sols.size)
        assertTrue(sols.contains(Value(2L)))
        assertTrue(sols.contains(Value(3L)))
    }

    @Test
    fun testNumericRootsAndExtrema() {
        // f(x) = x^2 - 4
        val x = Variable("x")
        val fn = Function1D(Additions(Power(x, Value.TWO), Value(-4L)), x)
        val roots = NumericRootFinder.findRoots(fn, -5.0, 5.0)
        assertEquals(2, roots.size)
        assertEquals(-2.0, roots[0], 1e-4)
        assertEquals(2.0, roots[1], 1e-4)

        val extrema = NumericRootFinder.findExtrema(fn, -5.0, 5.0)
        assertEquals(1, extrema.size)
        assertEquals(0.0, extrema[0], 1e-4)
    }

    @Test
    fun testIntegralNumericalEvaluation() {
        // int(0 to 1) x^2 dx = [1/3 x^3]_0^1 = 1/3
        val x = Variable("x")
        val integrand = Power(x, Value.TWO)
        val integral = Integral(Value.ZERO, Value.ONE, integrand, x)
        val res = integral.evaluateNumerically()
        assertEquals(0.333333333, res, 1e-6)
    }

    @Test
    fun testParserAndWriter() = runBlocking {
        // Build MathString: 2 * 3 + 4
        val input = MathString(
            listOf(
                NumberNode("2"),
                OperatorNode(MathOp.MULTIPLY),
                NumberNode("3"),
                OperatorNode(MathOp.PLUS),
                NumberNode("4")
            )
        )
        val res = MathEngine.calculate(input)
        assertTrue(res.isSuccess)
        assertEquals(10.0, res.numericValue, 1e-9)
        assertEquals("10", res.result.toString())
    }

    @Test
    fun testFractionNodeEvaluation() = runBlocking {
        // Fraction: 1 / 4 + 1 / 4 = 1 / 2
        val frac1 = FractionNode(
            MathString(listOf(NumberNode("1"))),
            MathString(listOf(NumberNode("4")))
        )
        val frac2 = FractionNode(
            MathString(listOf(NumberNode("1"))),
            MathString(listOf(NumberNode("4")))
        )
        val input = MathString(
            listOf(
                frac1,
                OperatorNode(MathOp.PLUS),
                frac2
            )
        )
        val res = MathEngine.calculate(input)
        assertTrue(res.isSuccess)
        assertEquals(0.5, res.numericValue, 1e-9)
        assertTrue(res.result.nodes.any { it is FractionNode })
    }

    @Test
    fun testEquationSolvingInCalculate() = runBlocking {
        // 2x - 6 = 0 -> x = 3
        val input = MathString(
            listOf(
                NumberNode("2"),
                VariableNode("x"),
                OperatorNode(MathOp.MINUS),
                NumberNode("6"),
                OperatorNode(MathOp.EQUALS),
                NumberNode("0")
            )
        )
        val res = MathEngine.calculate(input)
        assertTrue(res.isSuccess)
        assertEquals("x = 3", res.result.toString())
    }

    @Test
    fun testExactDecimalParsing() = runBlocking {
        // 0.125 + 0.125 = 1/4
        val input = MathString(
            listOf(
                NumberNode("0.125"),
                OperatorNode(MathOp.PLUS),
                NumberNode("0.125")
            )
        )
        val res = MathEngine.calculate(input)
        assertTrue(res.isSuccess)
        assertEquals(0.25, res.numericValue, 1e-9)
        val frac = res.result.nodes.firstOrNull { it is FractionNode } as? FractionNode
        assertNotNull(frac)
        assertEquals("1", frac!!.numerator.toString())
        assertEquals("4", frac.denominator.toString())
    }

    @Test
    fun testLargeBigIntPrecision() = runBlocking {
        val bigNum = "123456789012345678901234567890"
        val input = MathString(
            listOf(
                NumberNode(bigNum),
                OperatorNode(MathOp.PLUS),
                NumberNode("1")
            )
        )
        val res = MathEngine.calculate(input)
        assertTrue(res.isSuccess)
        assertEquals("123456789012345678901234567891", res.result.toString())
    }

    @Test
    fun testDefiniteIntegralExactResult() = runBlocking {
        val intNode = IntegralNode(
            lowerBound = MathString(listOf(NumberNode("0"))),
            upperBound = MathString(listOf(NumberNode("1"))),
            integrand = MathString(listOf(ExponentNode(MathString(listOf(VariableNode("x"))), MathString(listOf(NumberNode("2")))))),
            variable = "x"
        )
        val res = MathEngine.calculate(MathString(listOf(intNode)))
        assertTrue(res.isSuccess)
        assertEquals(0.333333333, res.numericValue, 1e-4)
        val frac = res.result.nodes.firstOrNull { it is FractionNode } as? FractionNode
        assertNotNull(frac)
        assertEquals("1", frac!!.numerator.toString())
        assertEquals("3", frac.denominator.toString())
    }
}
