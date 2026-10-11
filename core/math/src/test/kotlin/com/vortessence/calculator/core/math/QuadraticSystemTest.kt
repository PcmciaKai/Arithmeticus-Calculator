package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.LineBreakNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Systems of the second degree without an equation that is linear: squares only, or only terms of degree 2 and numbers. */
class QuadraticSystemTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun lines(text: String): List<String> {
        val result = calc(text)
        assertTrue(text, result.isSuccess)
        val parts = mutableListOf(StringBuilder())
        for (node in result.result.nodes) {
            if (node === LineBreakNode) parts.add(StringBuilder()) else parts.last().append(MathStringText.of(node))
        }
        return parts.map { clean(it.toString()) }
    }

    @Test
    fun onlySquaresAreALinearSystemInUAndV() {
        assertEquals(listOf("x = −4, y = −3", "x = −4, y = 3", "x = 4, y = −3", "x = 4, y = 3"), lines("x^2+y^2=25|x^2-y^2=7"))
        assertEquals(listOf("x = −2, y = −3", "x = −2, y = 3", "x = 2, y = −3", "x = 2, y = 3"), lines("x^2+y^2=13|2x^2+y^2=17"))
        assertEquals(listOf("x = −2, y = −sqrt(2)", "x = −2, y = sqrt(2)", "x = 2, y = −sqrt(2)", "x = 2, y = sqrt(2)"), lines("2x^2+3y^2=14|x^2-y^2=2"))
        // A square that is 0 has one root
        assertEquals(listOf("x = 0, y = 0"), lines("x^2+y^2=0|x^2-y^2=0"))
        // A negative square: no real solution
        assertEquals(MathIssue.NO_REAL_SOLUTION, calc("x^2+y^2=1|x^2+y^2=2").issues.first())
        // The same equation twice says nothing more
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, calc("x^2+y^2=25|2x^2+2y^2=50").issues.first())
    }

    @Test
    fun theTermsOfDegreeTwoGiveAnEquationOfTheRatio() {
        assertEquals(listOf("x = −2, y = −3", "x = 2, y = 3"), lines("x^2+x*y=10|y^2+x*y=15"))
        assertEquals(listOf("x = −3, y = −1", "x = 3, y = 1"), lines("x^2-x*y=6|x*y-y^2=2"))
        // With roots in the solutions: x² + xy + y² = 7 and x² − y² = 3
        val mixed = lines("x^2+x*y+y^2=7|x^2-y^2=3")
        assertEquals(4, mixed.size)
        assertTrue(mixed.contains("x = −2, y = −1"))
        assertTrue(mixed.contains("x = 2, y = 1"))
        assertTrue(mixed.any { it.contains("sqrt(3)") })
    }

    @Test
    fun stepsForSquaresPutTheSquaresInLetters() {
        val s = steps("x^2+y^2=25|x^2-y^2=7")!!
        val rules = s.steps.map { it.rule }
        assertEquals(StepRule.SUBSTITUTE_POWER, rules[1])
        assertTrue(rules.contains(StepRule.BACK_SUBSTITUTE_POWER))
        assertTrue(rules.contains(StepRule.SQUARE_ROOT_BOTH_SIDES))
        assertEquals("x = −4, y = −3 x = −4, y = 3 x = 4, y = −3 x = 4, y = 3", clean(s.result.toString()))
    }

    @Test
    fun stepsForTheRatioEliminateTheConstants() {
        val s = steps("x^2+x*y=10|y^2+x*y=15")!!
        val rules = s.steps.map { it.rule }
        // Multiplied so that the constants are the same, subtracted, then an equation of t = y/x
        assertEquals(StepRule.MULTIPLY_EQUATIONS, rules[1])
        assertTrue(rules.contains(StepRule.SUBTRACT_EQUATIONS))
        assertTrue(rules.contains(StepRule.SUBSTITUTE_RATIO))
        assertTrue(rules.contains(StepRule.QUADRATIC_FORMULA))
        // t = −1 leaves 0 = 10: that case has no solution
        assertTrue(rules.contains(StepRule.CONTRADICTION))
        assertEquals("x = −2, y = −3 x = 2, y = 3", clean(s.result.toString()))
        for (text in listOf("x^2-x*y=6|x*y-y^2=2", "x^2+x*y+y^2=7|x^2-y^2=3", "2x^2+3y^2=14|x^2-y^2=2")) assertNotNull(text, steps(text))
    }
}
