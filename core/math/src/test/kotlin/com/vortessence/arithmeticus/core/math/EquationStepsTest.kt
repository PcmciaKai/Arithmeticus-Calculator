package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import com.vortessence.arithmeticus.core.math.type.*
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
        // No solution: the steps end at 0 = 1
        assertEquals("no solution", solution(ms(x, eq, x, plus, n("1")))!!.result.toString())
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
        // Without complex numbers the equation has no solution: the discriminant is negative
        assertEquals("no real solution", solution(ms(x, pow(n("2")), plus, n("2"), x, plus, n("5"), eq, n("0")))!!.result.toString())
    }

    @Test
    fun exponentialEquationsByTheLogarithm() {
        // eˣ = 5: ln of both sides, exact although the calculator has 1.609…
        val natural = solution(ms(ConstantNode("e"), pow(x), eq, n("5")))!!
        assertEquals("ln", natural.steps.first().operation.toString())
        assertEquals("x = ln(5)", natural.result.toString())
        // 2ˣ + 1 = 9: the 1 first, then log₂
        val base2 = solution(ms(n("2"), pow(x), plus, n("1"), eq, n("9")))!!
        // The logarithm of both sides, then log₂(2ˣ) = x
        assertEquals(listOf(StepRule.LOGARITHM_BOTH_SIDES, StepRule.LOGARITHM_OF_POWER), base2.steps.map { it.rule }.filter { it == StepRule.LOGARITHM_BOTH_SIDES || it == StepRule.LOGARITHM_OF_POWER })
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

    @Test
    fun theProblemIsShownWithTheDecimalsThatWereTyped() {
        // 0.5x + 1.2 = 3
        val solution = solution(ms(n("0"), n("."), n("5"), x, plus, n("1"), n("."), n("2"), eq, n("3")))!!
        assertEquals("{0.5x + 1.2 = 3}", solution.steps.first().expression.toString().replace("\n", " "))
        // The calculation itself is exact
        assertEquals("x = (18)/(5)", solution.result.toString())
    }

    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))

    @Test
    fun logarithmicEquationsRaiseTheBase() {
        // ln(x) = 2: e^ on both sides, exact although the calculator has 7.389…
        val natural = solution(ms(f("ln", x), eq, n("2")))!!
        assertEquals("e^", natural.steps.first().operation.toString())
        assertEquals("x = (e)^(2)", natural.result.toString())
        // 2·ln(x) + 1 = 7: the 1, the 2, then e^
        val rules = solution(ms(n("2"), f("ln", x), plus, n("1"), eq, n("7")))!!.steps.map { it.rule }
        assertEquals(StepRule.EXPONENTIATE_BOTH_SIDES, rules.last())
        // log(x + 1) = 1: 10^ and then a linear equation
        assertEquals("x = 9", solution(ms(f("log", x, plus, n("1")), eq, n("1")))!!.result.toString())
    }

    @Test
    fun exponentialEquationsInAnExponentialOfTheVariableBySubstitution() {
        // e^(2x) − 3e^x + 2 = 0: u = eˣ, u = 2 or u = 1, then eˣ = 2 and eˣ = 1
        val solution = solution(ms(ConstantNode("e"), pow(n("2"), x), minus, n("3"), ConstantNode("e"), pow(x), plus, n("2"), eq, n("0")))!!
        val rules = solution.steps.map { it.rule }
        assertTrue(StepRule.SUBSTITUTE_POWER in rules)
        assertTrue(StepRule.BACK_SUBSTITUTE_POWER in rules)
        assertEquals("u = (e)^(x)", solution.steps[1].notes.single().toString())
        assertEquals("x = 0, x = ln(2)", solution.result.toString())
    }

    @Test
    fun squaredRootsAreChecked() {
        // √(x + 1) = x − 1: x = 0 or x = 3, and 0 is no solution
        val solution = solution(ms(RadicalNode(null, ms(x, plus, n("1"))), eq, x, minus, n("1")))!!
        val rules = solution.steps.map { it.rule }
        assertEquals(StepRule.SQUARE_BOTH_SIDES, rules[1])
        assertEquals("²", solution.steps.first().operation.toString())
        // The check crosses x = 0 out, the last line is what is left
        assertEquals(listOf(StepRule.CHECK_SOLUTIONS, StepRule.COLLECT_SOLUTION), rules.takeLast(2))
        assertEquals("sqrt(0 + 1) ≠ 0 − 1", solution.steps[solution.steps.size - 2].notes.first().toString())
        assertEquals("x = 3", solution.steps.last().expression.toString())
        assertEquals("x = 3", solution.result.toString())
    }

    @Test
    fun aDenominatorThatBecomesZeroIsExcluded() {
        // (x² − 1)/(x − 1) = 0: the numerator is 0 for x = ±1, but x ≠ 1
        val solution = solution(ms(FractionNode(ms(x, pow(n("2")), minus, n("1")), ms(x, minus, n("1"))), eq, n("0")))!!
        val rules = solution.steps.map { it.rule }
        assertEquals(StepRule.ZERO_FRACTION, rules[1])
        assertEquals("x − 1 ≠ 0", solution.steps[1].notes.single().toString())
        assertEquals(listOf(StepRule.CHECK_SOLUTIONS, StepRule.COLLECT_SOLUTION), rules.takeLast(2))
        assertEquals("x =  − 1", solution.steps.last().expression.toString())
        assertEquals("x =  − 1", solution.result.toString())
    }

    @Test
    fun absoluteValuesSplitIntoTwoCases() {
        // |x − 1| = 3
        val simple = solution(ms(f("abs", x, minus, n("1")), eq, n("3")))!!
        assertEquals(StepRule.ABSOLUTE_VALUE_CASES, simple.steps[1].rule)
        assertEquals("x =  − 2, x = 4", simple.result.toString())
        // |2x + 1| = x + 4: both solutions are checked, 2·(−5/3) + 1 included
        val withVariable = solution(ms(f("abs", n("2"), x, plus, n("1")), eq, x, plus, n("4")))!!
        assertEquals(StepRule.CHECK_SOLUTIONS, withVariable.steps.last().rule)
        // No solution for a negative number
        assertEquals("no real solution", solution(ms(f("abs", x), eq, OperatorNode(MathOp.MINUS), n("2")))!!.result.toString())
    }

    @Test
    fun trigonometricEquationsHaveAGeneralSolution() {
        // sin(x) = 1/2: x = π/6 + 2kπ or x = 5π/6 + 2kπ
        val sine = solution(ms(f("sin", x), eq, FractionNode(ms(n("1")), ms(n("2")))))!!
        assertEquals(StepRule.TRIG_GENERAL_SOLUTION, sine.steps[1].rule)
        assertEquals(listOf("k ∈ ℤ", "asin((1)/(2)) = (π)/(6)"), sine.steps[1].notes.map { it.toString() })
        assertEquals("x = (π)/(6) + 2πk, x = (5π)/(6) + 2πk, k ∈ ℤ", sine.result.toString())
        // tan repeats every π
        assertEquals("x = (π)/(4) + πk, k ∈ ℤ", solution(ms(f("tan", x), eq, n("1")))!!.result.toString())
        // 2x: the solutions are divided by 2
        val double = solution(ms(f("sin", n("2"), x), eq, FractionNode(ms(n("1")), ms(n("2")))))!!
        assertEquals("x = (π)/(12) + πk, x = (5π)/(12) + πk, k ∈ ℤ", double.result.toString())
        // |c| > 1: no solution
        assertEquals("no real solution", solution(ms(f("sin", x), eq, n("2")))!!.result.toString())
    }

    @Test
    fun polynomialsInASineOrCosineBySubstitution() {
        // 2sin²x − sin x − 1 = 0: u = sin x, u = 1 or u = −1/2, then the general solutions
        val solution = solution(ms(n("2"), f("sin", x), pow(n("2")), minus, f("sin", x), minus, n("1"), eq, n("0")))!!
        val rules = solution.steps.map { it.rule }
        assertEquals(StepRule.SUBSTITUTE_POWER, rules[1])
        assertEquals("u = sin(x)", solution.steps[1].notes.single().toString())
        assertTrue(StepRule.BACK_SUBSTITUTE_POWER in rules)
        assertEquals(2, rules.count { it == StepRule.TRIG_GENERAL_SOLUTION })
        assertEquals("x = (π)/(2) + (2πk)/(3), k ∈ ℤ", solution.result.toString())
        // sin²x = 1/4 needs no substitution: the square root first, sin x = ±1/2
        val square = solution(ms(f("sin", x), pow(n("2")), eq, FractionNode(ms(n("1")), ms(n("4")))))!!
        assertEquals(StepRule.SQUARE_ROOT_BOTH_SIDES, square.steps[1].rule)
        assertEquals(2, square.steps.count { it.rule == StepRule.TRIG_GENERAL_SOLUTION })
    }

    private fun calculated(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext()) }

    @Test
    fun rootsWhereTheFunctionOnlyTouchesZeroAreFound() {
        // sin²x + sin x = 2: sin x = 1 at π/2 + 2kπ, no sign change there
        val input = ms(f("sin", x), pow(n("2")), plus, f("sin", x), eq, n("2"))
        val result = calculated(input)
        assertTrue(result.issues.isEmpty())
        assertEquals("x = (π)/(2) + 2πk, k ∈ ℤ", result.result.toString())
        // And the steps for it: u = sin x, u = 1 (u = −2 has no real solution)
        val solution = solution(input)!!
        assertTrue(StepRule.NO_REAL_SOLUTION in solution.steps.map { it.rule })
        assertEquals("x = (π)/(2) + 2πk, k ∈ ℤ", solution.result.toString())
        // cos x = 1 is touched at 2kπ too, with the same precision
        assertEquals("x = 2πk, k ∈ ℤ", calculated(ms(f("cos", x), eq, n("1"))).result.toString())
        // A function that only comes close to 0 has no root
        assertEquals(listOf(com.vortessence.arithmeticus.core.math.context.MathIssue.NO_REAL_SOLUTION),
            calculated(ms(f("cos", x), pow(n("2")), plus, n("1"), eq, n("0"))).issues)
    }

    @Test
    fun sineEqualsCosineByDividingByTheCosine() {
        // sin x = cos x: everything to the left, ÷ cos x, sin/cos = tan, tan x = 1
        val solution = solution(ms(f("sin", x), eq, f("cos", x)))!!
        val rules = solution.steps.map { it.rule }
        assertEquals(StepRule.SUBTRACT_FROM_BOTH_SIDES, rules[1])
        val divide = solution.steps.first { it.rule == StepRule.DIVIDE_BOTH_SIDES }
        assertEquals(listOf("cos(x) ≠ 0"), divide.notes.map { it.toString() })
        assertTrue(StepRule.TANGENT_AS_QUOTIENT in rules)
        // The last line is the result with the k in it
        assertEquals(listOf(StepRule.TRIG_GENERAL_SOLUTION, StepRule.COLLECT_SOLUTION), rules.takeLast(2))
        assertEquals("x = (π)/(4) + πk, k ∈ ℤ", solution.steps.last().expression.toString())
        assertEquals("x = (π)/(4) + πk, k ∈ ℤ", solution.result.toString())
        // With coefficients and another argument: 2sin x = 3cos x gives atan(3/2), sin 2x = cos 2x the half
        assertEquals("x = atan((3)/(2)) + πk, k ∈ ℤ", solution(ms(n("2"), f("sin", x), eq, n("3"), f("cos", x)))!!.result.toString())
        assertEquals("x = (π)/(8) + (πk)/(2), k ∈ ℤ", solution(ms(f("sin", n("2"), x), eq, f("cos", n("2"), x)))!!.result.toString())
        // sin x + cos x = 0, with the cosine on the right of a minus
        assertEquals("x =  − (π)/(4) + πk, k ∈ ℤ", solution(ms(f("sin", x), plus, f("cos", x), eq, n("0")))!!.result.toString())
    }

    @Test
    fun sineAndCosineCombineIntoOneSine() {
        // sin x + cos x = 1: √2·sin(x + π/4) = 1
        val solution = solution(ms(f("sin", x), plus, f("cos", x), eq, n("1")))!!
        assertEquals(StepRule.AMPLITUDE_FORM, solution.steps[1].rule)
        assertEquals("x = 2πk, x = (π)/(2) + 2πk, k ∈ ℤ", solution.result.toString())
        // The terms are collected first: cos x = 1 − sin x
        val moved = solution(ms(f("cos", x), eq, n("1"), minus, f("sin", x)))!!
        assertEquals(solution.result.toString(), moved.result.toString())
        // Other coefficients: sin x + 2cos x = 1 has no exact angle, but the equation is still reduced
        val other = solution(ms(f("sin", x), plus, n("2"), f("cos", x), eq, n("1")))!!
        assertEquals(StepRule.AMPLITUDE_FORM, other.steps[1].rule)
        assertEquals("sqrt(5)sin(x + atan(2)) = 1", other.steps[1].expression.toString().replace("{", "").replace("}", "").replace("[", "").replace("]", ""))
        // c = 0 is the homogeneous case
        assertTrue(solution(ms(f("sin", x), plus, f("cos", x), eq, n("0")))!!.steps.none { it.rule == StepRule.AMPLITUDE_FORM })
    }

    @Test
    fun doubleAngleIsWrittenWithTheSingleAngle() {
        // sin 2x = cos x: 2sin x cos x = cos x, cos x (2sin x − 1) = 0
        val sine = solution(ms(f("sin", n("2"), x), eq, f("cos", x)))!!
        assertEquals(
            listOf(StepRule.DOUBLE_ANGLE, StepRule.SUBTRACT_FROM_BOTH_SIDES, StepRule.COMBINE_LIKE_TERMS, StepRule.FACTOR_COMMON, StepRule.ZERO_PRODUCT),
            sine.steps.drop(1).take(5).map { it.rule }
        )
        assertEquals(
            "x = (π)/(2) + 2πk, x = (π)/(6) + (2πk)/(3), k ∈ ℤ",
            sine.result.toString()
        )
        // cos 2x = sin x: 1 − 2sin²x = sin x, a quadratic in sin x
        val cosine = solution(ms(f("cos", n("2"), x), eq, f("sin", x)))!!
        assertEquals(StepRule.DOUBLE_ANGLE, cosine.steps[1].rule)
        assertTrue(cosine.steps.any { it.rule == StepRule.SUBSTITUTE_POWER })
        assertEquals(
            "x = (π)/(6) + (2πk)/(3), k ∈ ℤ",
            cosine.result.toString()
        )
    }

    private fun rootNode(vararg a: MathNode) = RadicalNode(null, ms(*a))

    @Test
    fun theRootOfASquareIsTheAbsoluteValue() {
        val simple = solution(ms(rootNode(x, pow(n("2")))))!!
        assertEquals(StepRule.ROOT_OF_SQUARE, simple.steps[1].rule)
        assertEquals("abs(x)", simple.result.toString())
        // √(x²) = 2 has the solutions 2 and −2
        val equation = solution(ms(rootNode(x, pow(n("2"))), eq, n("2")))!!
        assertEquals("x =  − 2, x = 2", equation.result.toString())
    }

    @Test
    fun aValueThatMakesADenominatorZeroIsNoSolution() {
        // (x² − 4)/(x − 2) = 4 is x + 2 = 4 once the factor is cancelled, but x = 2 is not allowed
        val input = ms(FractionNode(ms(x, pow(n("2")), minus, n("4")), ms(x, minus, n("2"))), eq, n("4"))
        val result = runBlocking { MathEngine.calculate(input) }
        assertFalse(result.isSuccess)
        // The steps cancel the factor, find x = 2 and cross it out
        val steps = solution(input)!!
        assertTrue(steps.steps.any { it.rule == StepRule.CHECK_SOLUTIONS })
        assertEquals("no real solution", steps.result.toString())
    }

    @Test
    fun twoAbsoluteValuesAreEqualWhenTheInsidesAreEqualOrOpposite() {
        // |2x − 1| = |x + 2|: 2x − 1 = x + 2 or 2x − 1 = −(x + 2)
        val solution = solution(ms(f("abs", n("2"), x, minus, n("1")), eq, f("abs", x, plus, n("2"))))!!
        assertEquals(StepRule.ABSOLUTE_VALUES_EQUAL, solution.steps[1].rule)
        assertEquals("x =  − (1)/(3), x = 3", solution.result.toString())
        assertTrue(solution.steps.size < 25)
    }

    @Test
    fun denominatorsOfSeveralTermsAreClearedOneAfterTheOther() {
        // 1/x + 1/(x + 1) = 1 has the exact solutions (1 ± √5)/2, not an approximation
        val input = ms(FractionNode(ms(n("1")), ms(x)), plus, FractionNode(ms(n("1")), ms(x, plus, n("1"))), eq, n("1"))
        val solution = solution(input)!!
        assertTrue(solution.steps.none { it.rule == StepRule.NEWTON_METHOD })
        assertEquals(2, solution.result.toString().split(", ").size)
        assertTrue(solution.result.toString().contains("sqrt(5)"))
    }

    @Test
    fun equationsWithoutASolutionShowWhy() {
        fun last(input: MathString) = solution(input)!!.let { it.steps.map { s -> s.rule } to it.result.toString() }
        // x² = −4
        assertEquals("no real solution", solution(ms(x, pow(n("2")), eq, minus, n("4")))!!.result.toString())
        // x + 1 = x
        val contradiction = solution(ms(x, plus, n("1"), eq, x))!!
        assertEquals("no solution", contradiction.result.toString())
        assertEquals(StepRule.CONTRADICTION, contradiction.steps.last().rule)
        // √x = −2
        assertEquals("no real solution", solution(ms(rootNode(x), eq, minus, n("2")))!!.result.toString())
        // (x + 1)/(x − 2) = 3/(x − 2): x = 2 comes out, but it is crossed out by the check
        val fractions = ms(
            FractionNode(ms(x, plus, n("1")), ms(x, minus, n("2"))), eq, FractionNode(ms(n("3")), ms(x, minus, n("2")))
        )
        val crossedOut = solution(fractions)!!
        assertTrue(crossedOut.steps.any { it.rule == StepRule.CHECK_SOLUTIONS })
        assertEquals("no real solution", crossedOut.result.toString())
        last(ms(x, pow(n("2")), eq, minus, n("4")))
    }

    @Test
    fun generalSolutionsThatAreEvenlySpreadAreOneFamily() {
        // cos x = 0: π/2 + 2πk and −π/2 + 2πk are π/2 + πk
        assertEquals("x = (π)/(2) + πk, k ∈ ℤ", solution(ms(f("cos", x), eq, n("0")))!!.result.toString())
        // sin x cos x = 0: every multiple of π/2
        assertEquals("x = (πk)/(2), k ∈ ℤ", solution(ms(f("sin", x), f("cos", x), eq, n("0")))!!.result.toString())
        // sin x = 1/2: the two solutions are not evenly spread
        assertEquals("x = (π)/(6) + 2πk, x = (5π)/(6) + 2πk, k ∈ ℤ", solution(ms(f("sin", x), eq, FractionNode(ms(n("1")), ms(n("2")))))!!.result.toString())
    }

    @Test
    fun amplitudeFormWithRootsInTheCoefficients() {
        // sin x + √3·cos x = 1: R = 2, φ = π/3, sin(x + π/3) = 1/2
        val input = ms(f("sin", x), plus, rootNode(n("3")), f("cos", x), eq, n("1"))
        val solution = solution(input)!!
        assertEquals(StepRule.AMPLITUDE_FORM, solution.steps[1].rule)
        assertEquals("x =  − (π)/(6) + 2πk, x = (π)/(2) + 2πk, k ∈ ℤ", solution.result.toString())
    }

    @Test
    fun tangentsAndSquaredSinesAgainstCosines() {
        // sin²x − cos²x = 0: 1 − 2cos²x = 0, cos x = ±√2/2: π/4 + πk/2
        val squares = solution(ms(f("sin", x), pow(n("2")), minus, f("cos", x), pow(n("2")), eq, n("0")))!!
        assertTrue(squares.steps.any { it.rule == StepRule.PYTHAGOREAN })
        assertEquals("x = (π)/(4) + (πk)/(2), k ∈ ℤ", squares.result.toString())
        // tan x = sin x: sin x = sin x cos x, sin x (1 − cos x) = 0: x = πk
        val tangent = solution(ms(f("tan", x), eq, f("sin", x)))!!
        assertEquals(StepRule.TANGENT_AS_FRACTION, tangent.steps[1].rule)
        assertEquals("x = πk, k ∈ ℤ", tangent.result.toString())
    }

    @Test
    fun trigonometricEquationsInDegrees() {
        val degrees = MathContext(angleUnit = com.vortessence.arithmeticus.core.math.context.AngleUnit.DEGREES)
        // sin x = 1/2: 30° and 150°, every 360°
        val sine = solution(ms(f("sin", x), eq, FractionNode(ms(n("1")), ms(n("2")))), degrees)!!
        assertEquals("x = 30 + 360k, x = 150 + 360k, k ∈ ℤ", sine.result.toString())
        assertEquals("x = 90 + 180k, k ∈ ℤ", solution(ms(f("cos", x), eq, n("0")), degrees)!!.result.toString())
        assertEquals("x = 45 + 180k, k ∈ ℤ", solution(ms(f("tan", x), eq, n("1")), degrees)!!.result.toString())
        // sin x + cos x = 1: 0° and 90°
        assertEquals("x = 360k, x = 90 + 360k, k ∈ ℤ", solution(ms(f("sin", x), plus, f("cos", x), eq, n("1")), degrees)!!.result.toString())
        // 2sin²x − sin x − 1 = 0
        val polynomial = solution(ms(n("2"), f("sin", x), pow(n("2")), minus, f("sin", x), minus, n("1"), eq, n("0")), degrees)!!
        assertEquals("x = 90 + 120k, k ∈ ℤ", polynomial.result.toString())
    }

    @Test
    fun logarithmsAreCombinedBeforeTheEquationIsSolved() {
        // ln x + ln(x − 1) = ln 6: ln(x(x − 1)) = ln 6, x(x − 1) = 6, x = 3 or x = −2, and ln(−2) is no value
        val input = ms(f("ln", x), plus, f("ln", x, minus, n("1")), eq, f("ln", n("6")))
        val solution = solution(input)!!
        assertEquals(listOf(StepRule.LOG_COMBINE, StepRule.LOG_EQUAL), solution.steps.drop(1).take(2).map { it.rule })
        assertTrue(solution.steps.any { it.rule == StepRule.CHECK_SOLUTIONS })
        assertEquals("x = 3", solution.result.toString())
    }
}
