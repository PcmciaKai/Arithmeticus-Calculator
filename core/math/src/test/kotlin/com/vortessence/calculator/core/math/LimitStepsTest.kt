package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LimitStepsTest {
    private fun n(s: String) = NumberNode(s)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private val x = VariableNode("x")
    private val minus = OperatorNode(MathOp.MINUS)
    private val plus = OperatorNode(MathOp.PLUS)
    private fun pow(vararg a: MathNode) = ExponentNode(null, ms(*a))
    private fun f(name: String, vararg a: MathNode) = FunctionNode(name, ms(*a))
    private fun frac(a: MathString, b: MathString) = FractionNode(a, b)
    private fun lim(to: String, vararg a: MathNode) = ms(LimitNode(ms(x, OperatorNode(MathOp.CONVERT), n(to)), ms(*a)))

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    private fun rules(input: MathString) = solution(input)?.steps?.drop(1)?.map { it.rule }

    @Test
    fun aFunctionDefinedAtThePointIsSubstituted() {
        // lim x→2 (x² + 1): 2² + 1, 4 + 1, 5
        val solution = solution(lim("2", x, pow(n("2")), plus, n("1")))!!
        assertEquals(StepRule.LIMIT_SUBSTITUTE, solution.steps[1].rule)
        assertEquals("5", solution.result.toString())
    }

    @Test
    fun zeroOverZeroByLHopitalWithTheDerivativesWorkedOut() {
        // lim x→0 sin(x)/x
        val solution = solution(lim("0", frac(ms(f("sin", x)), ms(x))))!!
        val rules = solution.steps.drop(1).map { it.rule }
        assertEquals(StepRule.LHOPITAL, rules.first())
        assertTrue(StepRule.SINE in rules)
        assertEquals(StepRule.LIMIT_SUBSTITUTE, rules.first { it == StepRule.LIMIT_SUBSTITUTE })
        // f(a) = 0 and g(a) = 0 are shown
        assertEquals(listOf("sin(0) = 0", "0 = 0"), solution.steps[1].notes.map { it.toString() })
        assertEquals("1", solution.result.toString())
    }

    @Test
    fun lHopitalAgainWhileItIsStillZeroOverZero() {
        // lim x→0 (1 − cos x)/x²: f′/g′ = sin(x)/(2x) is 0/0 again
        val rules = rules(lim("0", frac(ms(n("1"), minus, f("cos", x)), ms(x, pow(n("2"))))))!!
        assertEquals(2, rules.count { it == StepRule.LHOPITAL })
    }

    @Test
    fun commonFactorsAreCancelledBeforeAnythingElse() {
        // lim x→2 (x² − 4)/(x − 2): (x − 2)(x + 2)/(x − 2) = x + 2, no l'Hôpital needed
        val rules = rules(lim("2", frac(ms(x, pow(n("2")), minus, n("4")), ms(x, minus, n("2")))))!!
        assertEquals(StepRule.CANCEL_COMMON_FACTOR, rules.first())
        assertFalse(StepRule.LHOPITAL in rules)
        assertEquals("4", solution(lim("2", frac(ms(x, pow(n("2")), minus, n("4")), ms(x, minus, n("2")))))!!.result.toString())
    }

    private val inf = ConstantNode("∞")
    private fun limInf(vararg a: MathNode) = ms(LimitNode(ms(x, OperatorNode(MathOp.CONVERT), inf), ms(*a)))

    @Test
    fun aRationalFunctionAtInfinityIsDividedByTheHighestPower() {
        // lim x→∞ (2x² + 1)/(x² − 3): (2 + 1/x²)/(1 − 3/x²), the 1/x² vanish, 2/1 = 2
        val solution = solution(limInf(frac(ms(n("2"), x, pow(n("2")), plus, n("1")), ms(x, pow(n("2")), minus, n("3")))))!!
        val rules = solution.steps.drop(1).map { it.rule }
        assertEquals(listOf(StepRule.DIVIDE_BY_HIGHEST_POWER, StepRule.LIMIT_VANISH), rules.take(2))
        assertEquals(StepRule.LIMIT_CONSTANT, rules.last())
        assertEquals("2", solution.result.toString())
        // A lower degree in the numerator: 0
        assertEquals("0", solution(limInf(frac(ms(n("3"), x, plus, n("1")), ms(x, pow(n("2")), plus, n("1")))))!!.result.toString())
        // Towards −∞ the same
        val minusInfinity = ms(LimitNode(ms(x, OperatorNode(MathOp.CONVERT), minus, inf), ms(frac(ms(n("2"), x, plus, n("1")), ms(x, minus, n("3"))))))
        assertEquals("2", solution(minusInfinity)!!.result.toString())
    }

    @Test
    fun theHighestPowerWinsAndDecidesTheSign() {
        // lim x→∞ (x³ + 1)/(x² + 1) = ∞
        val solution = solution(limInf(frac(ms(x, pow(n("3")), plus, n("1")), ms(x, pow(n("2")), plus, n("1")))))!!
        assertEquals(listOf(StepRule.LIMIT_GROWS), solution.steps.drop(1).map { it.rule })
        assertEquals("∞", solution.result.toString())
    }

    @Test
    fun infinityOverInfinityByLHopital() {
        // lim x→∞ x/eˣ: l'Hôpital once, then 1/eˣ → 0
        val solution = solution(limInf(frac(ms(x), ms(ConstantNode("e"), pow(x)))))!!
        val rules = solution.steps.drop(1).map { it.rule }
        assertEquals(StepRule.LHOPITAL, rules.first())
        assertEquals(listOf("f(x) → ∞", "g(x) → ∞"), solution.steps[1].notes.map { it.toString() })
        assertEquals(StepRule.LIMIT_RECIPROCAL, rules.last())
        assertEquals("0", solution.result.toString())
        // lim x→∞ ln(x)/x
        assertEquals("0", solution(limInf(frac(ms(f("ln", x)), ms(x))))!!.result.toString())
        // No rule for 1^∞: no steps
        assertNull(solution(limInf(ParenthesesNode(ms(n("1"), plus, frac(ms(n("1")), ms(x)))), pow(x))))
    }
}
