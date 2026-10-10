package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemStepsTest {
    private fun n(s: String) = NumberNode(s)
    private fun v(s: String) = VariableNode(s)
    private val plus = OperatorNode(MathOp.PLUS)
    private val minus = OperatorNode(MathOp.MINUS)
    private val eq = OperatorNode(MathOp.EQUALS)

    private fun system(vararg lines: List<MathNode>): MathString {
        val nodes = mutableListOf<MathNode>()
        lines.forEachIndexed { i, line ->
            if (i > 0) nodes.add(LineBreakNode)
            nodes.addAll(line)
        }
        return MathString(nodes)
    }

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    // x + y = 3, 2x − y = 0
    private val simple = system(
        listOf(v("x"), plus, v("y"), eq, n("3")),
        listOf(n("2"), v("x"), minus, v("y"), eq, n("0"))
    )

    @Test
    fun substitutionSolvesOneEquationThenWritesItIntoTheOther() {
        val solution = solution(simple)!!
        val rules = solution.steps.drop(1).map { it.rule }
        assertEquals(StepRule.SOLVE_FOR, rules.first())
        assertEquals(1, rules.count { it == StepRule.SUBSTITUTE_SOLUTION })
        assertTrue(StepRule.INSERT_VALUE in rules)
        // The equation solved for x is shown on its own, with the operation next to it
        assertEquals(" − y", solution.steps[1].operation.toString())
        assertEquals("x = 1, y = 2", solution.result.toString())
    }

    @Test
    fun dividingByANegativeCoefficientIsOneStep() {
        val rules = solution(simple)!!.steps.map { it.rule }
        assertFalse(StepRule.NUMBER_POWER in rules)
    }

    @Test
    fun eliminationWhenNoVariableHasTheCoefficientOne() {
        // 2x + 3y = 7, 3x − 2y = 4
        val solution = solution(system(
            listOf(n("2"), v("x"), plus, n("3"), v("y"), eq, n("7")),
            listOf(n("3"), v("x"), minus, n("2"), v("y"), eq, n("4"))
        ))!!
        val rules = solution.steps.map { it.rule }
        assertTrue(StepRule.MULTIPLY_EQUATIONS in rules)
        assertTrue(StepRule.SUBTRACT_EQUATIONS in rules)
        assertFalse(StepRule.SUBSTITUTE_SOLUTION in rules)
        assertEquals("x = 2, y = 1", solution.result.toString())
        // The last line, framed as the result, has both values
        assertEquals("x = 2, y = 1", solution.steps.last().expression.toString())
    }

    @Test
    fun threeEquations() {
        // x + y + z = 6, 2y + z = 5, x − z = −1
        val solution = solution(system(
            listOf(v("x"), plus, v("y"), plus, v("z"), eq, n("6")),
            listOf(n("2"), v("y"), plus, v("z"), eq, n("5")),
            listOf(v("x"), minus, v("z"), eq, minus, n("1"))
        ))!!
        assertEquals("x = 2, y = 1, z = 3", solution.result.toString())
        assertEquals(2, solution.steps.count { it.rule == StepRule.SUBSTITUTE_SOLUTION })
    }

    @Test
    fun parallelLinesAndTheSameLineTwice() {
        // x + y = 1 and x + y = 2: one written into the other gives 1 = 2
        val parallel = solution(system(
            listOf(v("x"), plus, v("y"), eq, n("1")),
            listOf(v("x"), plus, v("y"), eq, n("2"))
        ))!!
        assertEquals("no solution", parallel.result.toString())
        assertEquals(StepRule.CONTRADICTION, parallel.steps.last().rule)
        // x + y = 1 and 2x + 2y = 2: 1 = 1, so every x with y = 1 − x
        val same = solution(system(
            listOf(v("x"), plus, v("y"), eq, n("1")),
            listOf(n("2"), v("x"), plus, n("2"), v("y"), eq, n("2"))
        ))!!
        assertEquals("x = 1 − y, y ∈ ℝ", same.result.toString())
    }

    @Test
    fun aValueOfOneStaysVisibleWhereItIsWrittenIn() {
        // y = 1 written into 2x + 3y = 7 is 2x + 3·1 = 7, not 2x + 3 = 7
        val solution = solution(system(
            listOf(n("2"), v("x"), plus, n("3"), v("y"), eq, n("7")),
            listOf(n("3"), v("x"), minus, n("2"), v("y"), eq, n("4"))
        ))!!
        val inserted = solution.steps.first { it.rule == StepRule.INSERT_VALUE }
        assertTrue(inserted.expression.toString(), inserted.expression.toString().contains("1"))
        assertEquals(StepRule.MULTIPLY_NUMBERS, solution.steps[solution.steps.indexOf(inserted) + 1].rule)
    }
}
