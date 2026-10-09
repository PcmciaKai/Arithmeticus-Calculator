package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** In the expected steps, {…} is the part the next step changes and […] the part the step produced. */
class SimplificationStepsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val y = VariableNode("y")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg e: MathNode) = ExponentNode(null, ms(*e))
    private fun par(vararg e: MathNode) = ParenthesesNode(ms(*e))
    private fun fr(numerator: MathString, denominator: MathString) = FractionNode(numerator, denominator)
    private val plus = op(MathOp.PLUS)
    private val minus = op(MathOp.MINUS)

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    private fun assertSteps(input: MathString, result: String, vararg expected: Triple<String, StepRule?, String?>) {
        val actual = solution(input) ?: return fail("No steps")
        assertEquals(
            expected.toList(),
            actual.steps.map { step -> Triple(step.expression.toString(), step.rule, step.change?.let { "${it.before} = ${it.after}" }) }
        )
        assertEquals(result, actual.result.toString())
    }

    private fun rules(input: MathString) = solution(input)?.steps?.drop(1)?.map { it.rule }

    @Test
    fun multiplyingOutAPowerOfASum() {
        assertSteps(
            ms(par(x, plus, n("1")), pow(n("2")), minus, x, pow(n("2"))),
            "2x + 1",
            Triple("{((x + 1))^(2)} − (x)^(2)", null, null),
            Triple("[{(x)^(2)} + 2x + 1] − {(x)^(2)}", StepRule.EXPAND_POWER, "((x + 1))^(2) = (x)^(2) + 2x + 1"),
            Triple("2x + 1", StepRule.COMBINE_LIKE_TERMS, "(x)^(2) − (x)^(2) = 0")
        )
    }

    @Test
    fun multiplyingOutProducts() {
        assertEquals(listOf(StepRule.EXPAND, StepRule.COMBINE_LIKE_TERMS), rules(ms(par(x, plus, n("1")), par(x, minus, n("1")))))
        assertEquals(listOf(StepRule.DISTRIBUTE), rules(ms(n("2"), par(x, plus, n("3")))))
    }

    @Test
    fun likeTermsAndFractions() {
        assertEquals(listOf(StepRule.COMBINE_LIKE_TERMS, StepRule.COMBINE_LIKE_TERMS), rules(ms(n("2"), x, plus, n("3"), y, minus, x, plus, y)))
        assertSteps(
            ms(fr(ms(x), ms(n("2"))), plus, fr(ms(x), ms(n("3")))),
            "(5x)/(6)",
            Triple("{(x)/(2)} + {(x)/(3)}", null, null),
            Triple("[(5x)/(6)]", StepRule.COMBINE_LIKE_TERMS, "(x)/(2) + (x)/(3) = (5x)/(6)")
        )
        // Combined over the common denominator rather than split up
        assertEquals(
            listOf(StepRule.COMBINE_FRACTIONS, StepRule.SUBTRACT_NUMBERS, StepRule.CANCEL),
            rules(ms(fr(ms(x, plus, n("1")), ms(x)), minus, fr(ms(n("1")), ms(x))))
        )
    }

    @Test
    fun powersAndCancelling() {
        assertEquals(listOf(StepRule.COMBINE_POWERS), rules(ms(x, pow(n("2")), x, pow(n("3")))))
        assertEquals(listOf(StepRule.POWER_OF_POWER), rules(ms(par(x, pow(n("2"))), pow(n("3")))))
        assertEquals(listOf(StepRule.POWER_OF_PRODUCT, StepRule.NUMBER_POWER), rules(ms(par(n("2"), x), pow(n("3")))))
        // 6x²/(3x): the fraction stays a fraction while its parts are simplified
        assertSteps(
            ms(fr(ms(n("6"), x, pow(n("2"))), ms(n("3"), x))),
            "2x",
            Triple("({6}(x)^(2))/({3}x)", null, null),
            Triple("([2]{(x)^(2)})/({x})", StepRule.DIVIDE_NUMBERS, "(6)/(3) = 2"),
            Triple("2[x]", StepRule.COMBINE_POWERS, "((x)^(2))/(x) = x")
        )
    }

    @Test
    fun resultIsTheCalculatedResult() {
        val inputs = listOf(
            ms(par(x, plus, n("1")), pow(n("2")), minus, x, pow(n("2"))),
            ms(par(x, plus, n("1")), par(x, minus, n("1"))),
            ms(fr(ms(n("2"), x, plus, n("4")), ms(n("2")))),
            ms(fr(ms(x), ms(x, pow(n("2"))))),
            ms(RadicalNode(null, ms(x, pow(n("2"))))),
            ms(fr(ms(x, plus, n("1")), ms(x)), minus, fr(ms(n("1")), ms(x))),
        )
        for (input in inputs) {
            val result = runBlocking { MathEngine.calculate(input) }
            assertEquals(input.toString(), result.result.toString(), solution(input)?.result.toString())
        }
    }

    @Test
    fun noStepsWhereThereIsNothingToDo() {
        // Already simplified, or kept as it is by the calculator
        assertNull(solution(ms(n("2"), x)))
        assertNull(solution(ms(par(x, plus, n("1")), pow(n("2")))))
        assertNull(solution(ms(fr(ms(n("1")), ms(x)), plus, fr(ms(n("1")), ms(y)))))
        // Plain arithmetic has no steps
        assertNull(solution(ms(n("2"), plus, n("3"))))
        // Nor do definitions (equations have steps of their own, see EquationStepsTest)
        assertNull(solution(ms(VariableNode("a"), op(MathOp.DEFINE), x, plus, x)))
    }
}
