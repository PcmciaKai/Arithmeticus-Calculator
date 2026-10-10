package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** In the expected steps, {…} is the part the next step changes and […] the part the step produced. */
class EquationStepsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg e: MathNode) = ExponentNode(null, ms(*e))
    private fun par(vararg e: MathNode) = ParenthesesNode(ms(*e))
    private val plus = op(MathOp.PLUS)
    private val minus = op(MathOp.MINUS)
    private val eq = op(MathOp.EQUALS)

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    private fun rules(input: MathString) = solution(input)?.steps?.drop(1)?.map { it.rule }

    @Test
    fun linearEquationBySameOperationOnBothSides() {
        val steps = solution(ms(n("3"), x, plus, n("5"), eq, n("2"), x, minus, n("1")))!!.steps
        assertEquals(
            listOf(
                Triple("{3x + 5 = 2x − 1}", " − 2x", null),
                Triple("{3x} + 5 − [{2x}] = 2x − 1 − [2x]", null, StepRule.SUBTRACT_FROM_BOTH_SIDES),
                Triple("[x] + 5 = {2x} − 1 − {2x}", null, StepRule.COMBINE_LIKE_TERMS),
                Triple("{x + 5 =  − 1}", " − 5", StepRule.COMBINE_LIKE_TERMS),
                Triple("x + {5} − [{5}] =  − 1 − [5]", null, StepRule.SUBTRACT_FROM_BOTH_SIDES),
                Triple("x = { − 1} − {5}", null, StepRule.SUBTRACT_NUMBERS),
                Triple("x = [ − 6]", null, StepRule.SUBTRACT_NUMBERS)
            ),
            steps.map { Triple(it.expression.toString(), it.operation?.toString(), it.rule) }
        )
        assertEquals("x =  − 6", solution(ms(n("3"), x, plus, n("5"), eq, n("2"), x, minus, n("1")))!!.result.toString())
    }

    @Test
    fun dividingByTheCoefficientAndSwapping() {
        assertEquals(
            listOf(StepRule.SWAP_SIDES, StepRule.SUBTRACT_FROM_BOTH_SIDES, StepRule.SUBTRACT_NUMBERS, StepRule.SUBTRACT_NUMBERS,
                StepRule.DIVIDE_BOTH_SIDES, StepRule.DIVIDE_NUMBERS, StepRule.DIVIDE_NUMBERS),
            rules(ms(n("5"), eq, n("2"), x, plus, n("1")))
        )
        // x/3 = 1 is multiplied by 3
        assertTrue(StepRule.MULTIPLY_BOTH_SIDES in rules(ms(FractionNode(ms(x), ms(n("3"))), plus, n("1"), eq, n("2")))!!)
    }

    @Test
    fun aSubtractedSideCancelsInOneStep() {
        // x² = 2x + 3 | − (2x + 3): on the right, 2x + 3 − (2x + 3) = 0 without multiplying out
        val steps = solution(ms(x, pow(n("2")), eq, n("2"), x, plus, n("3")))!!.steps
        assertEquals(StepRule.SUBTRACT_SAME, steps[2].rule)
        assertEquals("2x + 3 − (2x + 3) = 0", steps[2].change.let { "${it!!.before} = ${it.after}" })
        assertEquals(1, steps.count { it.rule == StepRule.DISTRIBUTE })
    }

    @Test
    fun variableInTheDenominator() {
        val solution = solution(ms(FractionNode(ms(n("2")), ms(x)), eq, n("4")))!!
        assertEquals("· x", solution.steps.first().operation.toString())
        // Exact, although the calculator solves it numerically (0.5)
        assertEquals("x = (1)/(2)", solution.result.toString())
    }

    @Test
    fun squareRootOfBothSides() {
        assertEquals(listOf(StepRule.SQUARE_ROOT_BOTH_SIDES, StepRule.ROOT_OF_NUMBER, StepRule.ROOT_OF_NUMBER), rules(ms(x, pow(n("2")), eq, n("9"))))
        // (x + 1)² = 4 as it is, not multiplied out; then one equation after the other
        val steps = solution(ms(par(x, plus, n("1")), pow(n("2")), eq, n("4")))!!
        assertEquals(StepRule.SQUARE_ROOT_BOTH_SIDES, steps.steps[1].rule)
        assertEquals("x = 1, x =  − 3", steps.result.toString())
        // 2x² − 3 = 5: + 3, ÷ 2, then the square root
        assertEquals(
            listOf(StepRule.ADD_TO_BOTH_SIDES, StepRule.DIVIDE_BOTH_SIDES, StepRule.SQUARE_ROOT_BOTH_SIDES),
            rules(ms(n("2"), x, pow(n("2")), minus, n("3"), eq, n("5")))!!.filter { it.toString().contains("SIDES") }
        )
    }

    @Test
    fun factoringAndZeroProducts() {
        assertEquals(
            listOf(StepRule.FACTOR_OUT, StepRule.ZERO_PRODUCT, StepRule.ADD_TO_BOTH_SIDES, StepRule.ADD_NUMBERS),
            rules(ms(x, pow(n("2")), minus, n("3"), x, eq, n("0")))
        )
        assertEquals(StepRule.ZERO_PRODUCT, rules(ms(par(x, minus, n("2")), par(x, plus, n("3")), eq, n("0")))!!.first())
    }

    @Test
    fun quadraticFormula() {
        val steps = solution(ms(x, pow(n("2")), eq, n("2"), x, plus, n("3")))!!.steps
        // Everything to the left first, then the formula with a, b, c and the discriminant
        assertEquals(" − (2x + 3)", steps.first().operation.toString())
        val formula = steps.first { it.rule == StepRule.QUADRATIC_FORMULA }
        assertEquals(
            listOf("a = 1", "b =  − 2", "c =  − 3", "D = (( − 2))^(2) − 4 × 1 × ( − 3) = 16"),
            formula.notes.map { it.toString() }
        )
        assertEquals("x = 3, x =  − 1", solution(ms(x, pow(n("2")), eq, n("2"), x, plus, n("3")))!!.result.toString())
    }

    @Test
    fun identity() {
        val solution = solution(ms(x, plus, n("1"), eq, n("1"), plus, x))!!
        assertEquals(StepRule.IDENTITY, solution.steps.last().rule)
        assertEquals("true", solution.result.toString())
    }

    @Test
    fun noStepsForEquationsSolvedOnlyNumerically() {
        // sin(x) = x/2
        assertNull(solution(ms(FunctionNode("sin", ms(x)), eq, FractionNode(ms(x), ms(n("2"))))))
        // No solution
        assertNull(solution(ms(x, eq, x, plus, n("1"))))
    }

    @Test
    fun aRationalRootSplitsOffAFactor() {
        val solution = solution(
            ms(x, pow(n("3")), minus, n("6"), x, pow(n("2")), plus, n("11"), x, minus, n("6"), eq, n("0"))
        )!!
        val rules = solution.steps.map { it.rule }
        assertEquals(StepRule.FACTOR_BY_ROOT, rules[1])
        assertEquals(StepRule.ZERO_PRODUCT, rules[2])
        assertTrue(StepRule.QUADRATIC_FORMULA in rules)
        assertEquals("P(1) = (1)^(3) − 6 × (1)^(2) + 11 × 1 − 6 = 0", solution.steps[1].notes.single().toString())
        // In the order of the calculator's solutions
        assertEquals("x = 1, x = 2, x = 3", solution.result.toString())
    }

    @Test
    fun aCaseWithoutRealSolutionsDropsOut() {
        // x³ = 8: x = 2, and x² + 2x + 4 = 0 has a negative discriminant
        val solution = solution(ms(x, pow(n("3")), eq, n("8")))!!
        assertTrue(StepRule.NO_REAL_SOLUTION in solution.steps.map { it.rule })
        assertEquals("x = 2", solution.result.toString())
    }

    @Test
    fun aDoubleRootIsListedOnce() {
        // x³ − 3x + 2 = (x − 1)²(x + 2)
        val solution = solution(ms(x, pow(n("3")), minus, n("3"), x, plus, n("2"), eq, n("0")))!!
        assertEquals("x =  − 2, x = 1", solution.result.toString())
    }
}
