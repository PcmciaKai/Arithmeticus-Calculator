package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.SolutionStep
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DerivativeStepsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun d(vararg body: MathNode, at: MathString? = null) = ms(DerivativeNode(ms(*body), at))

    private fun steps(input: MathString, angle: AngleUnit = AngleUnit.RADIANS): List<SolutionStep>? = runBlocking {
        MathEngine.derivativeSteps(input, MathContext(angleUnit = angle))
    }

    private fun assertSteps(input: MathString, vararg expected: Pair<String, StepRule?>) {
        val actual = steps(input) ?: return fail("No steps")
        assertEquals(expected.toList(), actual.map { it.expression.toString() to it.rule })
    }

    @Test
    fun sumConstantFactorAndPowerRule() {
        assertSteps(
            d(n("3"), x, pow(n("2")), op(MathOp.MINUS), n("2"), x, op(MathOp.PLUS), n("1")),
            "d/dx(3(x)^(2) − 2x + 1)" to null,
            // The constant term drops out, the subtracted one keeps its sign
            "d/dx(3(x)^(2)) − d/dx(2x)" to StepRule.SUM,
            "3d/dx((x)^(2)) − d/dx(2x)" to StepRule.CONSTANT_FACTOR,
            "3 × 2x − d/dx(2x)" to StepRule.POWER,
            "3 × 2x − 2" to StepRule.CONSTANT_FACTOR,
            "6x − 2" to StepRule.SIMPLIFY
        )
    }

    @Test
    fun productRule() {
        assertSteps(
            d(x, pow(n("2")), f("sin", x)),
            "d/dx((x)^(2)sin(x))" to null,
            "sin(x)d/dx((x)^(2)) + (x)^(2)d/dx(sin(x))" to StepRule.PRODUCT,
            "2xsin(x) + (x)^(2)d/dx(sin(x))" to StepRule.POWER,
            // Already as simple as the result, so there is no separate simplification step
            "2xsin(x) + (x)^(2)cos(x)" to StepRule.SINE
        )
    }

    @Test
    fun quotientRule() {
        assertSteps(
            d(FractionNode(ms(f("sin", x)), ms(x))),
            "d/dx((sin(x))/(x))" to null,
            "(xd/dx(sin(x)) − sin(x)d/dx(x))/((x)^(2))" to StepRule.QUOTIENT,
            "(xcos(x) − sin(x)d/dx(x))/((x)^(2))" to StepRule.SINE,
            "(xcos(x) − sin(x))/((x)^(2))" to StepRule.VARIABLE,
            "(cos(x))/(x) − (sin(x))/((x)^(2))" to StepRule.SIMPLIFY
        )
    }

    @Test
    fun chainRuleLeavesTheInnerDerivativeOpen() {
        val result = steps(d(f("sin", x, pow(n("2"))))) ?: return fail("No steps")
        assertEquals("cos((x)^(2))d/dx((x)^(2))", result[1].expression.toString())
        assertEquals(StepRule.SINE, result[1].rule)
        assertTrue(result[1].chainRule)
        assertEquals("2xcos((x)^(2))", result.last().expression.toString())
        // With the variable itself inside, there is no inner derivative
        assertFalse(steps(d(f("sin", x)))!![1].chainRule)
    }

    @Test
    fun generalPowerAndLogarithm() {
        assertSteps(
            d(x, pow(x)),
            "d/dx((x)^(x))" to null,
            "(x)^(x)d/dx(xln(x))" to StepRule.GENERAL_POWER,
            "(x)^(x)(ln(x)d/dx(x) + xd/dx(ln(x)))" to StepRule.PRODUCT,
            "(x)^(x)(ln(x) + xd/dx(ln(x)))" to StepRule.VARIABLE,
            "(x)^(x)(ln(x) + (x)/(x))" to StepRule.LOGARITHM,
            "(x)^(x)(ln(x) + 1)" to StepRule.SIMPLIFY
        )
    }

    @Test
    fun valueAtAPoint() {
        assertSteps(
            d(x, pow(n("2")), at = ms(n("3"))),
            "d/dx((x)^(2))|x=(3)" to null,
            "2x" to StepRule.POWER,
            "2 × 3" to StepRule.SUBSTITUTE,
            "6" to StepRule.EVALUATE
        )
    }

    @Test
    fun lastStepIsTheCalculatedResult() {
        val inputs = listOf(
            d(FractionNode(ms(x, op(MathOp.PLUS), n("1")), ms(x, op(MathOp.MINUS), n("1")))),
            d(RadicalNode(null, ms(x))),
            d(f("cos", n("3"), x, op(MathOp.PLUS), n("1"))),
            d(f("atan", x)),
            d(n("5")),
        )
        for (input in inputs) {
            val result = runBlocking { MathEngine.calculate(input) }
            assertEquals(result.result.toString(), steps(input)?.last()?.expression.toString())
        }
        val inDegrees = d(f("sin", x))
        val result = runBlocking { MathEngine.calculate(inDegrees, MathContext(angleUnit = AngleUnit.DEGREES)) }
        assertEquals(result.result.toString(), steps(inDegrees, AngleUnit.DEGREES)?.last()?.expression.toString())
    }

    @Test
    fun noStepsForOtherInput() {
        assertNull(steps(ms(x, pow(n("2")))))
        assertNull(steps(d(x, op(MathOp.FACTORIAL))))
    }

    @Test
    fun availabilityMatchesTheSteps() {
        val inputs = listOf(
            d(x, pow(n("2")), f("sin", x)),
            d(x, pow(x)),
            d(n("5")),
            d(n("2"), x, op(MathOp.FACTORIAL)),
            d(f("sin", x, op(MathOp.FACTORIAL))),
            ms(x, pow(n("2"))),
        )
        for (input in inputs) {
            assertEquals(input.toString(), steps(input) != null, MathEngine.hasDerivativeSteps(input))
        }
    }
}
