package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArithmeticStepsTest {
    private fun n(s: String) = NumberNode(s)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private val plus = OperatorNode(MathOp.PLUS)
    private val minus = OperatorNode(MathOp.MINUS)
    private val times = OperatorNode(MathOp.MULTIPLY)
    private val div = OperatorNode(MathOp.DIVIDE)
    private fun frac(a: String, b: String) = FractionNode(ms(n(a)), ms(n(b)))
    private fun sqrt(a: String) = RadicalNode(null, ms(n(a)))

    private fun solution(input: MathString): Solution? = runBlocking { MathEngine.steps(input, MathContext()) }

    private fun rules(input: MathString) = solution(input)?.steps?.drop(1)?.map { it.rule }

    @Test
    fun sumOfFractionsWithACommonDenominator() {
        // 1/2 + 1/3 = 3/6 + 2/6 = (3 + 2)/6 = 5/6
        val solution = solution(ms(frac("1", "2"), plus, frac("1", "3")))!!
        assertEquals(
            listOf(StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS, StepRule.ADD_NUMBERS),
            solution.steps.drop(1).map { it.rule }
        )
        // The problem is shown as typed, the notes give the least common multiple and each extension
        assertEquals("{(1)/(2)} + {(1)/(3)}", solution.steps.first().expression.toString())
        assertEquals(
            listOf("lcm(2, 3) = 6", "(1 × 3)/(2 × 3) = (3)/(6)", "(1 × 2)/(3 × 2) = (2)/(6)"),
            solution.steps[1].notes.map { it.toString() }
        )
        assertEquals("(5)/(6)", solution.result.toString())
    }

    @Test
    fun subtractionReducingAndWholeNumbers() {
        assertEquals(listOf(StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS, StepRule.SUBTRACT_NUMBERS), rules(ms(frac("3", "4"), minus, frac("1", "6"))))
        // 1/6 + 1/3: only the second one is extended, and 3/6 is reduced to 1/2
        assertEquals(
            listOf(StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS, StepRule.ADD_NUMBERS, StepRule.REDUCE_FRACTION),
            rules(ms(frac("1", "6"), plus, frac("1", "3")))
        )
        // The same denominator needs no extension; a whole number is written as a fraction
        assertEquals(listOf(StepRule.COMBINE_FRACTIONS, StepRule.ADD_NUMBERS), rules(ms(frac("1", "5"), plus, frac("2", "5"))))
        assertEquals("(5)/(2)", solution(ms(n("2"), plus, frac("1", "2")))!!.result.toString())
    }

    @Test
    fun productAndQuotientOfFractions() {
        // 2/3 · 4/5 = (2·4)/(3·5) = 8/15
        val product = solution(ms(frac("2", "3"), times, frac("4", "5")))!!
        assertEquals(StepRule.MULTIPLY_FRACTIONS, product.steps[1].rule)
        assertEquals("(8)/(15)", product.result.toString())
        // 3/4 ÷ 5/6: flipped, multiplied, reduced: 18/20 = 9/10
        val quotient = solution(ms(frac("3", "4"), div, frac("5", "6")))!!
        assertEquals(
            listOf(StepRule.DIVIDE_BY_FRACTION, StepRule.MULTIPLY_FRACTIONS, StepRule.MULTIPLY_NUMBERS, StepRule.MULTIPLY_NUMBERS, StepRule.REDUCE_FRACTION),
            quotient.steps.drop(1).map { it.rule }
        )
        assertEquals("(9)/(10)", quotient.result.toString())
    }

    @Test
    fun rootsWithASquareFactor() {
        // √12 = 2√3, with 12 = 4 × 3 as a note
        val root = solution(ms(sqrt("12")))!!
        assertEquals(listOf(StepRule.SIMPLIFY_ROOT), root.steps.drop(1).map { it.rule })
        assertEquals(listOf("12 = 4 × 3"), root.steps[1].notes.map { it.toString() })
        assertEquals("2sqrt(3)", root.result.toString())
        // √12 + √3: the roots are simplified first, then combined
        assertEquals(listOf(StepRule.SIMPLIFY_ROOT, StepRule.COMBINE_LIKE_TERMS), rules(ms(sqrt("12"), plus, sqrt("3"))))
    }

    @Test
    fun plainArithmeticHasNoSteps() {
        assertNull(solution(ms(n("2"), plus, n("3"))))
        assertNull(solution(ms(n("12"), times, n("7"))))
        // 1/2 alone, and a root that is a whole number, have nothing to work out
        assertNull(solution(ms(frac("1", "2"))))
        assertNull(solution(ms(sqrt("16"))))
    }

    private fun mixed(whole: String, numerator: String, denominator: String) =
        MixedFractionNode(ms(n(whole)), ms(n(numerator)), ms(n(denominator)))

    @Test
    fun mixedNumbersAreConvertedToImproperFractions() {
        // 2 1/3 + 1 1/2: shown as typed, then 7/3 + 3/2, and the fraction steps
        val solution = solution(ms(mixed("2", "1", "3"), plus, mixed("1", "1", "2")))!!
        assertEquals(
            listOf(StepRule.IMPROPER_FRACTION, StepRule.IMPROPER_FRACTION, StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS, StepRule.ADD_NUMBERS),
            solution.steps.drop(1).map { it.rule }
        )
        assertEquals(listOf("(2 × 3 + 1)/(3) = (7)/(3)"), solution.steps[1].notes.map { it.toString() })
        assertEquals("(23)/(6)", solution.result.toString())
        // A mixed number alone: just the conversion
        val single = solution(ms(mixed("2", "1", "3")))!!
        assertEquals(listOf(StepRule.IMPROPER_FRACTION), single.steps.drop(1).map { it.rule })
        assertEquals("(7)/(3)", single.result.toString())
    }

    private val pct = OperatorNode(MathOp.PERCENT)

    @Test
    fun aPercentIsWrittenAsAFraction() {
        // 15% × 80: 15% = 15/100 = 3/20, then the fraction steps, 3·80/20 = 12
        val product = solution(ms(n("1"), n("5"), pct, times, n("8"), n("0")))!!
        assertEquals(StepRule.PERCENT_AS_FRACTION, product.steps[1].rule)
        assertEquals(listOf("15% = (15)/(100)"), product.steps[1].notes.map { it.toString() })
        assertEquals("12", product.result.toString())
        // 200 + 10%: 10% = 1/10, then 2000/10 + 1/10 = 2001/10 (the result is 200.1 here)
        val sum = solution(ms(n("2"), n("0"), n("0"), plus, n("1"), n("0"), pct))!!
        assertEquals(
            listOf(StepRule.PERCENT_AS_FRACTION, StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS, StepRule.ADD_NUMBERS),
            sum.steps.drop(1).map { it.rule }
        )
        assertEquals("(2001)/(10)", sum.result.toString())
        // A percent alone, one that does not reduce, and a decimal one
        assertEquals("(1)/(2)", solution(ms(n("5"), n("0"), pct))!!.result.toString())
        assertEquals(emptyList<String>(), solution(ms(n("7"), pct))!!.steps[1].notes.map { it.toString() })
        assertEquals("12.5% = (1)/(8)", solution(ms(n("1"), n("2"), n("."), n("5"), pct, times, n("4"), n("0")))!!.steps[1].change.let { "${it!!.before} = ${it.after}" })
    }

    private fun alternative(input: MathString) = MathEngine.percentOfFirstAlternative(input)

    @Test
    fun aPlusOrMinusPercentHasAPocketCalculatorReading() {
        // 200 + 10% (200.1 here) is read as 200 + 200 × 10% = 220
        val plusPercent = alternative(ms(n("2"), n("0"), n("0"), plus, n("1"), n("0"), pct))!!
        assertEquals("200+200*10%", MathText.toPlainText(plusPercent.expression).replace(" ", "").replace("×", "*").replace("·", "*"))
        assertEquals("200", MathText.toPlainText(plusPercent.base))
        assertEquals("10", MathText.toPlainText(plusPercent.percent))
        assertEquals("220", runBlocking { MathEngine.calculate(plusPercent.expression, MathContext()) }.result.toString())
        // Minus, and a longer first part, which is put in parentheses
        val minusPercent = alternative(ms(n("2"), times, n("3"), minus, n("5"), pct))!!
        assertTrue(minusPercent.subtracts)
        assertEquals("(2*3)−(2*3)*5%", MathText.toPlainText(minusPercent.expression).replace(" ", "").replace("×", "*").replace("·", "*").replace("-", "−"))
        // No reading for other lines: a percent alone, in a product, or in the middle
        assertNull(alternative(ms(n("1"), n("0"), pct)))
        assertNull(alternative(ms(n("2"), n("0"), n("0"), times, n("1"), n("0"), pct)))
        assertNull(alternative(ms(n("2"), plus, n("3"), times, n("1"), n("0"), pct)))
        assertNull(alternative(ms(n("2"), plus, n("1"), n("0"), pct, plus, n("1"))))
    }

    @Test
    fun noRootInTheDenominator() {
        // 1/√2 = √2/2
        val solution = solution(ms(FractionNode(ms(n("1")), ms(RadicalNode(null, ms(n("2")))))))
        assertNotNull(solution)
        assertTrue(solution!!.steps.any { it.rule == StepRule.RATIONALIZE })
        assertEquals("(sqrt(2))/(2)", solution.result.toString())
        // √(4/9) = 2/3
        val root = solution(ms(RadicalNode(null, ms(frac("4", "9")))))!!
        assertEquals(StepRule.ROOT_OF_FRACTION, root.steps[1].rule)
        assertEquals("(2)/(3)", root.result.toString())
    }
}
