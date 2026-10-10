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

    private fun solution(input: MathString, context: MathContext = MathContext()): Solution? = runBlocking { MathEngine.steps(input, context) }

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
    fun severalApproximatedSolutionsAndNoSolution() {
        // sin(x) = x/2: Newton's method once for each of the three solutions
        val sine = solution(ms(FunctionNode("sin", ms(x)), eq, FractionNode(ms(x), ms(n("2")))))!!
        assertEquals(3, sine.steps.count { it.rule == StepRule.NEWTON_METHOD })
        // No solution: nothing to show
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
        // x³ + x = 0: x(x² + 1), and x² = −1 has no real solution
        val solution = solution(ms(x, pow(n("3")), plus, x, eq, n("0")))!!
        assertTrue(StepRule.NO_REAL_SOLUTION in solution.steps.map { it.rule })
        assertEquals("x = 0", solution.result.toString())
    }

    @Test
    fun aRootOfBothSides() {
        // x³ − 2 = 0: the 2 to the right, then the cube root, exact although the calculator has 1.2599…
        val cube = solution(ms(x, pow(n("3")), minus, n("2"), eq, n("0")))!!
        assertEquals(listOf(StepRule.ADD_TO_BOTH_SIDES, StepRule.ADD_NUMBERS, StepRule.NTH_ROOT_BOTH_SIDES), cube.steps.drop(1).map { it.rule })
        assertEquals("³√", cube.steps[2].operation.toString())
        // Even powers have two roots, odd ones one, also of a negative number
        assertEquals("x =  − 2, x = 2", solution(ms(x, pow(n("4")), eq, n("16")))!!.result.toString())
        assertEquals("x =  − 2", solution(ms(n("2"), x, pow(n("5")), eq, minus, n("64")))!!.result.toString())
    }

    @Test
    fun aDoubleRootIsListedOnce() {
        // x³ − 3x + 2 = (x − 1)²(x + 2)
        val solution = solution(ms(x, pow(n("3")), minus, n("3"), x, plus, n("2"), eq, n("0")))!!
        assertEquals("x =  − 2, x = 1", solution.result.toString())
    }

    private fun v(name: String) = VariableNode(name)

    @Test
    fun quadraticsWithLettersAsCoefficients() {
        // ax² + bx + c = 0: the formula with the discriminant as it is, without multiplying it out again
        val solution = solution(ms(v("a"), x, pow(n("2")), plus, v("b"), x, plus, v("c"), eq, n("0")))!!
        assertEquals(listOf(StepRule.QUADRATIC_FORMULA), solution.steps.drop(1).map { it.rule })
        assertEquals("D = (b)^(2) − 4 × a × c = (b)^(2) − 4ac", solution.steps[1].notes.last().toString())
        assertEquals("x = ( − b + sqrt((b)^(2) − 4ac))/(2a), x = ( − b − sqrt((b)^(2) − 4ac))/(2a)", solution.result.toString())
    }

    @Test
    fun complexSolutionsOfAQuadratic() {
        val complex = MathContext(complexNumbers = true)
        // x² + 2x + 5 = 0: D = −16, √(−16) = i·√16 = 4i
        val solution = solution(ms(x, pow(n("2")), plus, n("2"), x, plus, n("5"), eq, n("0")), complex)!!
        assertTrue(StepRule.IMAGINARY_ROOT in solution.steps.map { it.rule })
        assertEquals("x =  − 1 + 2i, x =  − 1 − 2i", solution.result.toString())
        // Without complex numbers the equation has no solution, hence no steps
        assertNull(solution(ms(x, pow(n("2")), plus, n("2"), x, plus, n("5"), eq, n("0"))))
    }

    @Test
    fun exponentialEquationsByTheLogarithm() {
        // eˣ = 5: ln of both sides, exact although the calculator has 1.609…
        val natural = solution(ms(ConstantNode("e"), pow(x), eq, n("5")))!!
        assertEquals("ln", natural.steps.first().operation.toString())
        assertEquals("x = ln(5)", natural.result.toString())
        // 2ˣ + 1 = 9: the 1 first, then log₂
        val base2 = solution(ms(n("2"), pow(x), plus, n("1"), eq, n("9")))!!
        assertEquals(StepRule.LOGARITHM_BOTH_SIDES, base2.steps.map { it.rule }.last { it != StepRule.SIMPLIFY })
        assertEquals("x = 3", base2.result.toString())
    }

    @Test
    fun evenPowersBySubstitution() {
        // x⁴ − 5x² + 6 = 0: u = x², u = 3 or u = 2, then x² = 3 and x² = 2
        val solution = solution(ms(x, pow(n("4")), minus, n("5"), x, pow(n("2")), plus, n("6"), eq, n("0")))!!
        val rules = solution.steps.map { it.rule }
        assertTrue(StepRule.SUBSTITUTE_POWER in rules)
        assertTrue(StepRule.BACK_SUBSTITUTE_POWER in rules)
        assertEquals("u = (x)^(2)", solution.steps[1].notes.single().toString())
        assertEquals("x =  − sqrt(3), x =  − sqrt(2), x = sqrt(2), x = sqrt(3)", solution.result.toString())
    }

    @Test
    fun approximatedSolutionsByNewtonsMethod() {
        // cos(x) = x
        val solution = solution(ms(FunctionNode("cos", ms(x)), eq, x))!!
        assertEquals(listOf(StepRule.ONE_SIDE, StepRule.NEWTON_METHOD), solution.steps.drop(1).map { it.rule })
        val notes = solution.steps.last().notes.map { it.toString() }
        assertEquals("f′(x) =  − sin(x) − 1", notes.first())
        assertEquals("x₀ = 1", notes[1])
        assertEquals("x = 0.7390851332", solution.result.toString())
    }
}
