package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
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
            listOf(StepRule.INTEGRAL_SUM, StepRule.INTEGRAL_CONSTANT_FACTOR, StepRule.INTEGRAL_POWER, StepRule.INTEGRAL_COSINE, StepRule.MULTIPLY_NUMBERS),
            steps.drop(1).map { it.rule }
        )
        assertEquals("int ((x)^(2)) dx = ((x)^(3))/(3)", steps[3].change.let { "${it!!.before} = ${it.after}" })
        // Only the end result has the constant of integration
        assertEquals("(x)^(3) + sin(x) + C", steps.last().expression.toString())
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
}
