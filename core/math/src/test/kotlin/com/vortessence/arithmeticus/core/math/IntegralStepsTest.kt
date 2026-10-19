package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IntegralStepsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg e: MathNode) = ExponentNode(null, ms(*e))
    private fun par(vararg e: MathNode) = ParenthesesNode(ms(*e))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun integral(vararg e: MathNode) = ms(AntiderivativeNode(ms(*e), "x"))
    private fun integral(a: String, b: String, vararg e: MathNode) = ms(IntegralNode(ms(n(a)), ms(n(b)), ms(*e), "x"))
    private val e = ConstantNode("e")
    private val plus = op(MathOp.PLUS)
    private val minus = op(MathOp.MINUS)

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    private fun rules(input: MathString) = solution(input)?.steps?.drop(1)?.map { it.rule }

    @Test
    fun sumConstantFactorAndTableIntegrals() {
        val steps = solution(integral(n("3"), x, pow(n("2")), plus, f("cos", x)))!!.steps
        assertEquals(
            listOf(StepRule.INTEGRAL_SUM, StepRule.INTEGRAL_CONSTANT_FACTOR, StepRule.INTEGRAL_POWER, StepRule.INTEGRAL_COSINE, StepRule.DIVIDE_NUMBERS),
            steps.drop(1).map { it.rule }
        )
        assertEquals("int ((x)^(2)) dx = ((x)^(3))/(3)", steps[3].change.let { "${it!!.before} = ${it.after}" })
        // Only the end result has the constant of integration
        assertEquals("(x)^(3) + sin(x) + C", steps.last().expression.toString())
    }

    @Test
    fun standardIntegralOfTheTypedFunction() {
        // The typed sin(x) has no angle unit yet; the rules need the calculated one
        assertEquals(StepRule.HALF_ANGLE, rules(integral(f("sin", x), pow(n("2"))))!!.first())
        assertEquals(StepRule.INTEGRAL_SINE, rules(integral(f("sin", x)))!!.first())
    }

    @Test
    fun linearArgument() {
        val steps = solution(integral(f("sin", n("3"), x)))!!.steps
        assertEquals(StepRule.INTEGRAL_SINE, steps[1].rule)
        assertTrue(steps[1].chainRule)
        assertEquals("[ − (cos(3x))/(3)] + C", steps[1].expression.toString())
    }

    @Test
    fun substitution() {
        val steps = solution(integral(n("2"), x, f("cos", x, pow(n("2")))))!!.steps
        val substitution = steps.first { it.rule == StepRule.SUBSTITUTION }
        assertEquals(listOf("u = (x)^(2)", "du = 2x dx"), substitution.notes.map { it.toString() })
        assertEquals("int ((cos(u))/(2)) du", substitution.change!!.after.toString())
        assertTrue(steps.any { it.rule == StepRule.BACK_SUBSTITUTION })
        assertEquals("sin((x)^(2)) + C", steps.last().expression.toString())
    }

    @Test
    fun substitutionChosenAsByHand() {
        // u = x² for x/(1 + x⁴), although x² does not appear in it
        val steps = solution(integral(FractionNode(ms(x), ms(n("1"), plus, x, pow(n("4"))))))!!.steps
        assertEquals(listOf("u = (x)^(2)", "du = 2x dx"), steps.first { it.rule == StepRule.SUBSTITUTION }.notes.map { it.toString() })
        assertEquals("(atan((x)^(2)))/(2) + C", steps.last().expression.toString().replace(Regex("[\\[\\]{}]"), ""))
        // The inner function, x², not the whole e^(x²)
        val exponential = solution(integral(x, e, pow(x, pow(n("2")))))!!.steps
        assertEquals("u = (x)^(2)", exponential.first { it.rule == StepRule.SUBSTITUTION }.notes.first().toString())
    }

    @Test
    fun integrationByParts() {
        val steps = solution(integral(x, e, pow(x)))!!.steps
        assertEquals(StepRule.BY_PARTS, steps[1].rule)
        assertEquals(listOf("u = x", "dv = (e)^(x) dx", "du = dx", "v = (e)^(x)"), steps[1].notes.map { it.toString() })
        // u = atan(x), dv = dx, then a substitution for the rest
        assertEquals(StepRule.BY_PARTS, rules(integral(f("atan", x)))!!.first())
        assertTrue(StepRule.SUBSTITUTION in rules(integral(f("atan", x)))!!)
    }

    @Test
    fun rootsAndFractionsWrittenAsPowers() {
        assertEquals(
            listOf(StepRule.REWRITE_AS_POWER, StepRule.INTEGRAL_POWER, StepRule.DIVIDE_BY_FRACTION),
            rules(integral(RadicalNode(null, ms(x))))
        )
        assertEquals(
            listOf(StepRule.REWRITE_AS_POWER, StepRule.INTEGRAL_POWER, StepRule.POWER_AS_FRACTION),
            rules(integral(FractionNode(ms(n("1")), ms(x, pow(n("2"))))))
        )
        // 1/x has a rule of its own
        assertEquals(listOf(StepRule.INTEGRAL_RECIPROCAL), rules(integral(FractionNode(ms(n("1")), ms(x)))))
    }

    @Test
    fun multiplyingOutTheIntegrand() {
        assertEquals(StepRule.EXPAND_INTEGRAND, rules(integral(x, par(x, plus, n("1")), pow(n("2"))))!!.first())
    }

    @Test
    fun definiteIntegral() {
        val steps = solution(integral("0", "2", x, pow(n("2"))))!!.steps
        assertEquals(
            listOf(StepRule.ANTIDERIVATIVE, StepRule.INTEGRAL_POWER, StepRule.BOUNDS, StepRule.NUMBER_POWER, StepRule.NUMBER_POWER),
            steps.drop(1).map { it.rule }
        )
        assertEquals("[((x)^(3))/(3)]_(0)^(2) = ((2)^(3))/(3) − ((0)^(3))/(3)", steps[3].change.let { "${it!!.before} = ${it.after}" })
        assertEquals("(8)/(3)", steps.last().expression.toString())
    }

    @Test
    fun resultIsTheCalculatedResult() {
        val inputs = listOf(
            integral(x, pow(n("3")), minus, n("2"), x, plus, n("5")),
            integral(FractionNode(ms(n("1")), ms(x))),
            integral(e, pow(n("2"), x)),
            integral(x, f("ln", x)),
            integral(f("ln", x)),
            integral(par(n("2"), x, plus, n("1")), pow(n("5"))),
            integral(FractionNode(ms(n("1")), ms(n("1"), plus, x, pow(n("2"))))),
            integral(f("atan", x)),
            integral(RadicalNode(null, ms(x))),
            integral("1", "3", FractionNode(ms(n("1")), ms(x))),
            integral("0", "1", x, e, pow(x)),
        )
        for (input in inputs) {
            val result = runBlocking { MathEngine.calculate(input) }
            assertEquals(input.toString(), result.result.toString(), solution(input)?.result.toString())
        }
    }

    @Test
    fun noStepsWithoutAnAntiderivative() {
        // e^(x²) has no elementary antiderivative; its definite integral is only calculated numerically
        assertNull(solution(integral(e, pow(x, pow(n("2"))))))
        assertNull(solution(integral("0", "1", e, pow(x, pow(n("2"))))))
    }

    private fun frac(a: MathString, b: MathString) = FractionNode(a, b)

    @Test
    fun partialFractionsForADenominatorThatSplits() {
        // ∫ 1/(x² − 1) dx = ½ln|x − 1| − ½ln|x + 1|
        val solution = solution(integral(frac(ms(n("1")), ms(x, pow(n("2")), minus, n("1")))))!!
        assertEquals(StepRule.PARTIAL_FRACTIONS, solution.steps[1].rule)
        // x² − 1 = (x + 1)(x − 1), the setup, A and B
        assertEquals(4, solution.steps[1].notes.size)
        assertTrue(StepRule.INTEGRAL_RECIPROCAL in solution.steps.map { it.rule })
        assertEquals(" − (ln(abs(x + 1)))/(2) + (ln(abs(x − 1)))/(2) + C", solution.result.toString())
    }

    @Test
    fun partialFractionsWithADivisionAndARepeatedFactor() {
        // x³/(x² − 1): the polynomial part first, then the split of x/(x² − 1)
        val division = solution(integral(frac(ms(x, pow(n("3"))), ms(x, pow(n("2")), minus, n("1")))))!!
        // The division, the factors, the setup, A and B
        assertEquals(5, division.steps[1].notes.size)
        // 1/(x(x + 1)²): A/(x + 1) + B/(x + 1)² + C/x
        val repeated = solution(integral(frac(ms(n("1")), ms(x, par(x, plus, n("1")), pow(n("2"))))))!!
        assertEquals(StepRule.PARTIAL_FRACTIONS, repeated.steps[1].rule)
        assertEquals("A =  − 1", repeated.steps[1].notes[repeated.steps[1].notes.size - 3].toString())
        // A substitution is still preferred where one fits: ∫ x/(x² − 1) dx
        assertEquals(StepRule.SUBSTITUTION, solution(integral(frac(ms(x), ms(x, pow(n("2")), minus, n("1")))))!!.steps[1].rule)
    }

    @Test
    fun quadraticDenominatorWithoutRealRoots() {
        // ∫ 1/(x² + 2x + 5) dx: complete the square, u = x + 1, atan
        val completed = solution(integral(frac(ms(n("1")), ms(x, pow(n("2")), plus, n("2"), x, plus, n("5")))))!!
        assertEquals(
            listOf(StepRule.COMPLETE_SQUARE, StepRule.SUBSTITUTION, StepRule.INTEGRAL_TABLE, StepRule.BACK_SUBSTITUTION),
            completed.steps.drop(1).map { it.rule }.take(4)
        )
        assertEquals("(x)^(2) + 2x + 5 = ((x + 1))^(2) + 4", completed.steps[1].notes.single().toString())
        // ∫ x/(x² + x + 1) dx: the derivative of the denominator is split off first
        val split = solution(integral(frac(ms(x), ms(x, pow(n("2")), plus, x, plus, n("1")))))!!
        assertEquals(StepRule.SPLIT_NUMERATOR, split.steps[1].rule)
        assertTrue(StepRule.COMPLETE_SQUARE in split.steps.map { it.rule })
        assertTrue(StepRule.INTEGRAL_RECIPROCAL in split.steps.map { it.rule })
        // The numerator is the derivative: u = x² + 2x + 5 straight away
        assertEquals(StepRule.SUBSTITUTION, solution(integral(frac(ms(n("2"), x, plus, n("2")), ms(x, pow(n("2")), plus, n("2"), x, plus, n("5")))))!!.steps[1].rule)
    }

    @Test
    fun theAbsoluteValueOfANumberIsTheNumber() {
        // ∫₁ᵉ 1/x dx = ln|e| − ln|1| = 1
        val input = ms(IntegralNode(ms(n("1")), ms(ConstantNode("e")), ms(FractionNode(ms(n("1")), ms(VariableNode("x")))), "x"))
        assertEquals("1", runBlocking { MathEngine.steps(input, MathContext()) }!!.result.toString())
    }

    @Test
    fun squaredSinesAndCosinesByTheDoubleAngle() {
        val x = VariableNode("x")
        val steps = runBlocking { MathEngine.steps(ms(AntiderivativeNode(ms(FunctionNode("sin", ms(x)), ExponentNode(null, ms(n("2")))))), MathContext()) }!!
        assertEquals(StepRule.HALF_ANGLE, steps.steps[1].rule)
        assertEquals("(x)/(2) − (sin(x)cos(x))/(2) + C", steps.result.toString())
    }

    @Test
    fun polynomialDivisionAndCyclicParts() {
        val x = VariableNode("x")
        val e = ConstantNode("e")
        // ∫ x³/(x² + 1) dx = x²/2 − ln(x² + 1)/2
        val fraction = runBlocking { MathEngine.steps(ms(AntiderivativeNode(ms(FractionNode(ms(x, ExponentNode(null, ms(n("3")))), ms(x, ExponentNode(null, ms(n("2"))), OperatorNode(MathOp.PLUS), n("1")))))), MathContext()) }!!
        assertEquals(StepRule.POLYNOMIAL_DIVISION, fraction.steps[1].rule)
        assertEquals("((x)^(2))/(2) − (ln(abs((x)^(2) + 1)))/(2) + C", fraction.result.toString())
        // ∫ eˣ sin(x) dx = eˣ(sin x − cos x)/2
        val cyclic = runBlocking { MathEngine.steps(ms(AntiderivativeNode(ms(e, ExponentNode(null, ms(x)), FunctionNode("sin", ms(x))))), MathContext()) }!!
        assertEquals(StepRule.CYCLIC_BY_PARTS, cyclic.steps[1].rule)
        assertEquals("I = ((e)^(x)(sin(x) − cos(x)))/(2)", cyclic.steps[1].notes.last().toString())
        assertEquals("((e)^(x)(sin(x) − cos(x)))/(2) + C", cyclic.result.toString())
    }

    /** ∫ outer from a to b of (∫ inner from c to d of body d[y]) dx */
    private fun double(a: String, b: String, c: String, d: String, body: String, inner: String = "y", outer: String = "x") =
        ms(IntegralNode(TestInput.parse(a), TestInput.parse(b), ms(IntegralNode(TestInput.parse(c), TestInput.parse(d), TestInput.parse(body), inner)), outer))

    @Test
    fun doubleIntegralInnerFirst() {
        // ∫₀³ ∫₀ˣ (2xy − x² − y²) dy dx: the inner integral in y (x is a constant) with the bounds 0 and x, then the outer one
        val steps = solution(double("0", "3", "0", "x", "(2xy - x^2 - y^2)"))!!
        val rules = steps.steps.drop(1).map { it.rule }
        assertEquals(StepRule.ANTIDERIVATIVE, rules.first())
        assertEquals(2, rules.count { it == StepRule.BOUNDS })
        assertEquals(2, rules.count { it == StepRule.ANTIDERIVATIVE })
        // The first BOUNDS leaves ∫₀³ … dx; the inner integral is gone by then
        val firstBounds = steps.steps.first { it.rule == StepRule.BOUNDS }
        assertTrue(firstBounds.expression.toString().startsWith("int_{0}^{3}"))
        assertFalse(firstBounds.expression.toString().contains("dy"))
        assertEquals("− (27)/(4)", steps.result.toString().replace("  ", " ").trim())
        assertEquals(steps.result.toString(), steps.steps.last().expression.toString().replace(Regex("[\\[\\]{}]"), ""))
    }

    @Test
    fun doubleIntegralWithAnExactResult() {
        // ∫₀^π ∫₀ˣ sin(y) dy dx = π
        assertEquals("π", solution(double("0", "pi", "0", "x", "sin(y)"))!!.result.toString())
        // The outer variable is a constant for the inner integral: ∫₀² ∫₀ˣ x·y² dy dx = 32/15
        assertEquals("(32)/(15)", solution(double("0", "2", "0", "x", "x*y^2"))!!.result.toString())
    }

    @Test
    fun tripleIntegral() {
        val triple = ms(IntegralNode(TestInput.parse("0"), TestInput.parse("1"), ms(IntegralNode(TestInput.parse("0"), TestInput.parse("x"), ms(IntegralNode(TestInput.parse("0"), TestInput.parse("y"), TestInput.parse("z"), "z")), "y")), "x"))
        val steps = solution(triple)!!
        assertEquals(3, steps.steps.count { it.rule == StepRule.BOUNDS })
        assertEquals("(1)/(24)", steps.result.toString())
    }

    @Test
    fun noStepsWhereTheInnerIntegralHasNoElementaryAntiderivative() {
        // ∫₀¹ ∫ₓ¹ e^(y²) dy dx
        assertNull(solution(double("0", "1", "x", "1", "e^{y^2}")))
    }
}
