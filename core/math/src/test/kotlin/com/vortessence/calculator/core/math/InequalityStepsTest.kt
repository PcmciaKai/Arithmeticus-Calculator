package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InequalityStepsTest {
    private fun solution(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun rules(text: String) = solution(text)?.steps?.map { it.rule }

    @Test
    fun linearInequalitiesTurnAroundWhereTheyAreDividedByANegativeNumber() {
        val solution = solution("-3x + 1 < 7")!!
        assertEquals("x > −2", clean(solution.result.toString()))
        assertTrue(StepRule.DIVIDE_BY_NEGATIVE in solution.steps.map { it.rule })
        // Every line is an inequality, turned around at the division
        assertEquals("−3x < 6", clean(solution.steps[3].expression.toString()).replace("{", "").replace("}", "").replace("[", "").replace("]", "").trim())
        // Not for a positive one
        val positive = solution("2x + 1 < 7")!!
        assertEquals("x < 3", clean(positive.result.toString()))
        assertFalse(StepRule.DIVIDE_BY_NEGATIVE in positive.steps.map { it.rule })
        assertEquals("x ≥ 2", clean(solution("3x - 1 >= 5")!!.result.toString()))
    }

    @Test
    fun theSignOfTheLeftSideInEachInterval() {
        // x² − 4 > 0: the zeros ±2 from the equation, then one value in each interval
        val solution = solution("x^2 - 4 > 0")!!
        assertEquals(
            listOf(StepRule.INEQUALITY_ZEROS, StepRule.INEQUALITY_CRITICAL, StepRule.INEQUALITY_RESULT),
            solution.steps.map { it.rule }.filter { it in listOf(StepRule.INEQUALITY_ZEROS, StepRule.INEQUALITY_BREAKS, StepRule.INEQUALITY_CRITICAL, StepRule.INEQUALITY_RESULT) }
        )
        assertEquals("x < −2 or x > 2", clean(solution.result.toString()))
        val tests = solution.steps.last().notes.map { clean(it.toString()) }
        assertEquals(5, tests.size)
        assertTrue(tests.first(), tests.first().endsWith("= 5 > 0 ✓"))
        assertTrue(tests[2], tests[2].endsWith("= −4 < 0 ✗"))
        assertTrue(tests[1], tests[1].endsWith("= 0 = 0 ✗"))
        // Both sides not 0: everything to the left first
        assertEquals(StepRule.INEQUALITY_ONE_SIDE, solution("x^2 < 4x - 3")!!.steps[1].rule)
        assertEquals("1 < x < 3", clean(solution("x^2 < 4x - 3")!!.result.toString()))
    }

    @Test
    fun denominatorsRootsAndHoles() {
        // The zero of the numerator is in, the pole is not
        val rational = solution("[x - 1;x + 2] >= 0")!!
        assertTrue(StepRule.INEQUALITY_BREAKS in rational.steps.map { it.rule })
        assertEquals("x < −2 or x ≥ 1", clean(rational.result.toString()))
        assertTrue(clean(rational.steps.last().notes[1].toString()).endsWith("not defined ✗"))
        // (x² − 1)/(x − 1) > 0: x = 1 is crossed out as a zero, and is a break
        val hole = solution("[x^2 - 1;x - 1] > 0")!!
        assertTrue(StepRule.CHECK_SOLUTIONS in hole.steps.map { it.rule })
        assertEquals("−1 < x < 1 or x > 1", clean(hole.result.toString()))
        // A root starts at 0, √x = 2 at 4
        assertEquals("0 ≤ x < 4", clean(solution("sqrt(x) < 2")!!.result.toString()))
        assertEquals("−2 < x < 4", clean(solution("abs(x - 1) < 3")!!.result.toString()))
    }

    @Test
    fun noSolutionAndEverything() {
        val none = solution("x^2 + 1 < 0")!!
        assertEquals("no real solution", clean(none.result.toString()))
        assertEquals("x ∈ ℝ", clean(solution("x^2 + 1 > 0")!!.result.toString()))
        // Nothing to solve for, or not of this kind: no steps
        assertNull(solution("3 < 5"))
        assertNull(solution("sin(x) > 0"))
        assertNull(solution("x + y > 1"))
    }

    @Test
    fun theStepsEndWithTheCalculatedResult() {
        for (text in listOf("x^2 - 5x + 6 >= 0", "(x - 1)(x - 2)(x - 3) > 0", "[x - 3;x + 2] < 0", "[1;x] < [1;2]", "ln(x) > 0", "e^x > 1", "x^4 >= 1")) {
            val steps = solution(text)
            val calculated = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }
            assertNotNull(text, steps)
            assertEquals(text, clean(calculated.result.toString()), clean(steps!!.result.toString()))
        }
    }
}
