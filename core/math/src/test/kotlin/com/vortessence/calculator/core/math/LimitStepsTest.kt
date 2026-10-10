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
    fun factorsThatCancelAreDifferentiatedToo() {
        // lim x→2 (x² − 4)/(x − 2) = 4
        val solution = solution(lim("2", frac(ms(x, pow(n("2")), minus, n("4")), ms(x, minus, n("2")))))!!
        assertEquals("4", solution.result.toString())
    }

    @Test
    fun noStepsAtInfinity() {
        assertNull(solution(ms(LimitNode(ms(x, OperatorNode(MathOp.CONVERT), ConstantNode("∞")), ms(frac(ms(n("1")), ms(x)))))))
    }
}
