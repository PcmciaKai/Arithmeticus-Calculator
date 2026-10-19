package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * In the expected steps, {…} is the part the next step changes and […] the part the step
 * produced (see [HighlightNode.toString]).
 */
class DerivativeStepsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun d(vararg body: MathNode, at: MathString? = null) = ms(DerivativeNode(ms(*body), at))

    private fun solution(input: MathString, angle: AngleUnit = AngleUnit.RADIANS): Solution? = runBlocking {
        MathEngine.steps(input, MathContext(angleUnit = angle))
    }

    /** The steps as (expression, rule, change) and the result. */
    private fun assertSteps(input: MathString, result: String, vararg expected: Triple<String, StepRule?, String?>) {
        val actual = solution(input) ?: return fail("No steps")
        assertEquals(
            expected.toList(),
            actual.steps.map { step -> Triple(step.expression.toString(), step.rule, step.change?.let { "${it.before} = ${it.after}" }) }
        )
        assertEquals(result, actual.result.toString())
    }

    @Test
    fun constantFactorPowerRuleAndMultiplyingNumbers() {
        assertSteps(
            d(n("2"), x, pow(n("2"))),
            "4x",
            Triple("{d/dx(2(x)^(2))}", null, null),
            Triple("[2{d/dx((x)^(2))}]", StepRule.CONSTANT_FACTOR, "d/dx(2(x)^(2)) = 2d/dx((x)^(2))"),
            Triple("{2} × [{2}x]", StepRule.POWER, "d/dx((x)^(2)) = 2x"),
            Triple("[4]x", StepRule.MULTIPLY_NUMBERS, "2 × 2 = 4")
        )
    }

    @Test
    fun eachStepHasItsOwnColor() {
        val steps = solution(d(n("2"), x, pow(n("2"))))!!.steps
        fun highlights(math: MathString): List<Pair<Int, Boolean>> = math.nodes.flatMap { node ->
            (if (node is HighlightNode) listOf(node.colorIndex to node.boxed) else emptyList()) +
                node.subStrings.flatMap { highlights(it) }
        }
        // Step i boxes what it produced in color i and colors what step i + 1 changes
        assertEquals(listOf(1 to false), highlights(steps[0].expression))
        assertEquals(listOf(1 to true, 2 to false), highlights(steps[1].expression))
        assertEquals(listOf(3 to false, 2 to true, 3 to false), highlights(steps[2].expression))
        assertEquals(listOf(3 to true), highlights(steps[3].expression))
    }

    @Test
    fun sumRuleDropsConstantTerms() {
        assertSteps(
            d(n("3"), x, pow(n("2")), op(MathOp.MINUS), n("2"), x, op(MathOp.PLUS), n("1")),
            "6x − 2",
            Triple("{d/dx(3(x)^(2) − 2x + 1)}", null, null),
            Triple("[{d/dx(3(x)^(2))} − d/dx(2x)]", StepRule.SUM, "d/dx(3(x)^(2) − 2x + 1) = d/dx(3(x)^(2)) − d/dx(2x)"),
            Triple("[3{d/dx((x)^(2))}] − d/dx(2x)", StepRule.CONSTANT_FACTOR, "d/dx(3(x)^(2)) = 3d/dx((x)^(2))"),
            Triple("3 × [2x] − {d/dx(2x)}", StepRule.POWER, "d/dx((x)^(2)) = 2x"),
            Triple("{3} × {2}x − [2]", StepRule.CONSTANT_FACTOR, "d/dx(2x) = 2"),
            Triple("[6]x − 2", StepRule.MULTIPLY_NUMBERS, "3 × 2 = 6")
        )
    }

    @Test
    fun productRule() {
        assertSteps(
            d(x, pow(n("2")), f("sin", x)),
            "2xsin(x) + (x)^(2)cos(x)",
            Triple("{d/dx((x)^(2)sin(x))}", null, null),
            Triple("[sin(x){d/dx((x)^(2))} + (x)^(2)d/dx(sin(x))]", StepRule.PRODUCT, "d/dx((x)^(2)sin(x)) = sin(x)d/dx((x)^(2)) + (x)^(2)d/dx(sin(x))"),
            Triple("[2x]sin(x) + (x)^(2){d/dx(sin(x))}", StepRule.POWER, "d/dx((x)^(2)) = 2x"),
            Triple("2xsin(x) + (x)^(2)[cos(x)]", StepRule.SINE, "d/dx(sin(x)) = cos(x)")
        )
    }

    @Test
    fun quotientRuleThenMultiplyingOutAndCombining() {
        val steps = solution(d(FractionNode(ms(x, op(MathOp.PLUS), n("1")), ms(x, op(MathOp.MINUS), n("1")))))!!.steps
        assertEquals(StepRule.QUOTIENT, steps[1].rule)
        assertEquals(
            listOf(StepRule.DISTRIBUTE, StepRule.COMBINE_LIKE_TERMS, StepRule.SUBTRACT_NUMBERS),
            steps.takeLast(3).map { it.rule }
        )
        // The sign stays outside the sum until it is multiplied in
        assertEquals(" − (x + 1) =  − x − 1", steps.takeLast(3).first().change.let { "${it!!.before} = ${it.after}" })
    }

    @Test
    fun chainRuleWithOuterAndInnerDerivative() {
        assertSteps(
            d(f("sin", x, pow(n("2")))),
            "2xcos((x)^(2))",
            Triple("{d/dx(sin((x)^(2)))}", null, null),
            Triple("[{d/du(sin(u))}d/dx((x)^(2))]", StepRule.CHAIN_RULE, "d/dx(sin((x)^(2))) = d/du(sin(u))d/dx((x)^(2))"),
            Triple("{[cos(u)]}d/dx((x)^(2))", StepRule.SINE, "d/du(sin(u)) = cos(u)"),
            Triple("[cos((x)^(2))]{d/dx((x)^(2))}", StepRule.BACK_SUBSTITUTION, "u = (x)^(2)"),
            Triple("[2x]cos((x)^(2))", StepRule.POWER, "d/dx((x)^(2)) = 2x")
        )
        // The inner function is named
        assertEquals(listOf("u = (x)^(2)"), solution(d(f("sin", x, pow(n("2")))))!!.steps[1].notes.map { it.toString() })
        // With the variable itself inside, there is no chain rule
        assertEquals(listOf(StepRule.SINE), solution(d(f("sin", x)))!!.steps.drop(1).map { it.rule })
    }

    @Test
    fun rootWrittenAsPowerFirst() {
        // √(3x) = (3x)^(1/2), then the chain rule, and the power back as a root at the end
        assertEquals(
            listOf(
                StepRule.REWRITE_AS_POWER, StepRule.CHAIN_RULE, StepRule.POWER, StepRule.BACK_SUBSTITUTION,
                StepRule.CONSTANT_FACTOR, StepRule.POWER_AS_ROOT, StepRule.SIMPLIFY
            ),
            solution(d(RadicalNode(null, ms(n("3"), x))))!!.steps.drop(1).map { it.rule }
        )
        val steps = solution(d(FractionNode(ms(n("1")), ms(x))))!!.steps
        assertEquals(listOf(StepRule.REWRITE_AS_POWER, StepRule.POWER, StepRule.POWER_AS_FRACTION), steps.drop(1).map { it.rule })
        assertEquals("(1)/(x) = (x)^( − 1)", steps[1].change.let { "${it!!.before} = ${it.after}" })
        assertEquals("d/dx((x)^( − 1)) =  − (x)^( − 2)", steps[2].change.let { "${it!!.before} = ${it.after}" })
    }

    @Test
    fun cancelling() {
        val steps = solution(d(x, pow(x)))!!.steps
        assertEquals(StepRule.GENERAL_POWER, steps[1].rule)
        assertEquals(StepRule.CANCEL, steps.last().rule)
        assertEquals("(x)/(x) = 1", steps.last().change.let { "${it!!.before} = ${it.after}" })
        assertEquals("(x)^(x)(ln(x) + 1)", steps.last().expression.toString())
    }

    @Test
    fun valueAtAPoint() {
        assertSteps(
            d(x, pow(n("3")), at = ms(n("2"))),
            "12",
            Triple("{d/dx((x)^(3))|x=(2)}", null, null),
            Triple("[3({x})^(2)]", StepRule.POWER, "d/dx((x)^(3)) = 3(x)^(2)"),
            Triple("{3([2])^(2)}", StepRule.SUBSTITUTE, "x = 2"),
            Triple("[12]", StepRule.EVALUATE, "3 × (2)^(2) = 12")
        )
    }

    @Test
    fun resultIsTheCalculatedResult() {
        val inputs = listOf(
            d(FractionNode(ms(x, op(MathOp.PLUS), n("1")), ms(x, op(MathOp.MINUS), n("1")))),
            d(FractionNode(ms(f("sin", x)), ms(x))),
            d(ParenthesesNode(ms(x, op(MathOp.PLUS), n("1"))), pow(n("2"))),
            d(RadicalNode(null, ms(x))),
            d(f("cos", n("3"), x, op(MathOp.PLUS), n("1"))),
            d(f("atan", x)),
            d(n("5")),
        )
        for (input in inputs) {
            val result = runBlocking { MathEngine.calculate(input) }
            val solution = solution(input) ?: return fail("No steps for $input")
            assertEquals(result.result.toString(), solution.result.toString())
        }
        val inDegrees = d(f("sin", x))
        val result = runBlocking { MathEngine.calculate(inDegrees, MathContext(angleUnit = AngleUnit.DEGREES)) }
        assertEquals(result.result.toString(), solution(inDegrees, AngleUnit.DEGREES)?.result.toString())
    }

    @Test
    fun noStepsForOtherInput() {
        assertNull(solution(ms(x, pow(n("2")))))
        assertNull(solution(d(x, op(MathOp.FACTORIAL))))
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
            assertEquals(input.toString(), solution(input) != null, runBlocking { MathEngine.hasSteps(input) })
        }
    }
}
