package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.ImplicitCurve
import com.vortessence.calculator.core.math.solvers.ImplicitPlotter
import com.vortessence.calculator.core.math.solvers.NumericIntegrator
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.solvers.PlotCurve
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

    @Test
    fun plotAnalysisFindsInflectionPointsAndClassifiesExtrema() = runBlocking {
        // f(x) = x^3 - 3x: maximum at -1, minimum at 1, inflection point at 0
        val x = Variable("x")
        val fn = Function1D(Additions(Power(x, Value(3L)), Multiplications(Value(-3L), x)), x)
        val data = MathEngine.analyzePlot(fn, -3.0, 3.0)
        assertEquals(1, data.maxima.size)
        assertEquals(-1.0, data.maxima[0].x, 1e-4)
        assertEquals(1, data.minima.size)
        assertEquals(1.0, data.minima[0].x, 1e-4)
        assertEquals(1, data.inflectionPoints.size)
        assertEquals(0.0, data.inflectionPoints[0].x, 1e-4)

        // Straight lines and poles have no inflection points
        val line = Function1D(Additions(Multiplications(Value(2L), x), Value(1L)), x)
        assertTrue(NumericRootFinder.findInflectionPoints(line, -5.0, 5.0).isEmpty())
        val reciprocal = Function1D(Power(x, Value.MINUS_ONE), x)
        assertTrue(NumericRootFinder.findInflectionPoints(reciprocal, -5.0, 5.0).isEmpty())
    }

    @Test
    fun numericIntegratorComputesSignedArea() {
        val x = Variable("x")
        val square = Function1D(Power(x, Value.TWO), x)
        assertEquals(1.0 / 3.0, NumericIntegrator.integrate(square, 0.0, 1.0), 1e-12)
        assertEquals(-1.0 / 3.0, NumericIntegrator.integrate(square, 1.0, 0.0), 1e-12)

        val sine = Function1D(TrigonometricFunction(TrigType.SIN, x, AngleUnit.RADIANS), x)
        assertEquals(2.0, NumericIntegrator.integrate(sine, 0.0, Math.PI), 1e-10)
        assertEquals(0.0, NumericIntegrator.integrate(sine, -Math.PI, Math.PI), 1e-10)

        // Across a pole the integral diverges
        val reciprocal = Function1D(Power(x, Value.MINUS_ONE), x)
        assertTrue(NumericIntegrator.integrate(reciprocal, -1.0, 1.3).isNaN())
        assertEquals(kotlin.math.ln(2.0), NumericIntegrator.integrate(reciprocal, 1.0, 2.0), 1e-10)
    }

    @Test
    fun equationsWithYOnBothSidesBecomeImplicitCurves() {
        val x = Variable("x")
        val y = Variable("y")
        // y = x² + y² is the circle around (0, 0.5) with radius 0.5
        val circle = PlotCurve.fromExpression(Equals(y, Additions(Power(x, Value.TWO), Power(y, Value.TWO))))
        assertTrue(circle is ImplicitCurve)
        assertTrue(PlotCurve.fromExpression(Equals(y, Power(x, Value.TWO))) is Function1D)
        // Other variables than x and y cannot be plotted
        assertNull(PlotCurve.fromExpression(Equals(y, Additions(x, Variable("z")))))
        assertNull(PlotCurve.fromExpression(Equals(Additions(x, y), Variable("z"))))

        val curve = circle as ImplicitCurve
        val segments = ImplicitPlotter.contour(curve, -2.0, 2.0, -2.0, 2.0, 80, 80)
        assertTrue(segments.size > 20)
        for (segment in segments) {
            for (p in listOf(segment.start, segment.end)) {
                assertEquals(0.5, kotlin.math.hypot(p.x, p.y - 0.5), 1e-3)
            }
        }

        val (maxima, minima) = ImplicitPlotter.horizontalTangents(curve, segments)
        assertEquals(1, maxima.size)
        assertEquals(0.0, maxima[0].x, 1e-6)
        assertEquals(1.0, maxima[0].y, 1e-6)
        assertEquals(1, minima.size)
        assertEquals(0.0, minima[0].y, 1e-6)

        val onCurve = curve.projectOntoCurve(0.4, 0.9)!!
        assertEquals(0.5, kotlin.math.hypot(onCurve.x, onCurve.y - 0.5), 1e-9)
        // Tangent at the rightmost point is vertical, at 45° it has slope -1
        assertTrue(curve.slopeAt(0.5, 0.5).isInfinite())
        val r = 0.5 / kotlin.math.sqrt(2.0)
        assertEquals(-1.0, curve.slopeAt(r, 0.5 + r), 1e-6)
        assertEquals(listOf(1.0), ImplicitPlotter.yIntercepts(curve, 0.2, 2.0).map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun implicitContourSkipsPoles() {
        // 1/(x - y) = 1 is the line y = x - 1; F changes sign across y = x without a zero there
        val x = Variable("x")
        val y = Variable("y")
        val curve = PlotCurve.fromExpression(Equals(Power(Additions(x, y.negate()), Value.MINUS_ONE), Value.ONE)) as ImplicitCurve
        val segments = ImplicitPlotter.contour(curve, -3.0, 3.0, -3.0, 3.0, 60, 60)
        assertTrue(segments.isNotEmpty())
        for (segment in segments) assertEquals(segment.start.x - 1.0, segment.start.y, 1e-3)
    }

    @Test
    fun implicitContourOnLogGrid() {
        // x·y = 1 over five decades: a log-spaced grid still finds the curve at both ends
        val x = Variable("x")
        val y = Variable("y")
        val curve = PlotCurve.fromExpression(Equals(Multiplications(x, y), Value.ONE)) as ImplicitCurve
        val segments = ImplicitPlotter.contour(curve, 0.01, 100.0, 0.01, 100.0, 50, 50, logX = true, logY = true)
        val points = segments.flatMap { listOf(it.start, it.end) }
        for (p in points) assertEquals(1.0, p.x * p.y, 1e-3)
        assertTrue(points.any { it.x < 0.05 } && points.any { it.x > 20.0 })
    }
}
