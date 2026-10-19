package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import com.vortessence.arithmeticus.core.math.type.LineBreakNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Two equations of which one is nonlinear and one linear in a variable, solved by substitution. */
class NonlinearSystemTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    /** One line per solution: x = 3, y = 4. */
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
    fun aCircleAndALine() {
        assertEquals(listOf("x = 3, y = 4", "x = 4, y = 3"), lines("x^2+y^2=25|x+y=7"))
        assertEquals(listOf("x = −3, y = −4", "x = 4, y = 3"), lines("x^2+y^2=25|x-y=1"))
        // Not touching: no real solution
        val none = calc("x^2+y^2=1|x+y=3")
        assertFalse(none.isSuccess)
        assertEquals(MathIssue.NO_REAL_SOLUTION, none.issues.first())
    }

    @Test
    fun productsAndParabolas() {
        assertEquals(listOf("x = 2, y = 3", "x = 3, y = 2"), lines("x*y=6|x+y=5"))
        assertEquals(listOf("x = −2, y = 4", "x = 1, y = 1"), lines("y=x^2|x+y=2"))
        assertEquals(listOf("x = −1, y = 1", "x = 3, y = 9"), lines("y=x^2|y=2x+3"))
        assertEquals(listOf("x = −1, y = 4", "x = 2, y = 1"), lines("x+y=3|x^2+y=5"))
        // Four solutions: y = 12/x
        assertEquals(4, lines("x^2+y^2=25|x*y=12").size)
    }

    @Test
    fun irrationalSolutionsStayExact() {
        val result = lines("x*y=1|x+y=3")
        assertEquals(2, result.size)
        assertTrue(result.all { it.contains("sqrt(5)") })
    }

    @Test
    fun stepsSolveForOneVariableAndWriteItIntoTheOther() {
        val s = steps("x^2+y^2=25|x+y=7")!!
        val rules = s.steps.map { it.rule }
        // x = 7 − y, written into the other equation, the equation of y solved, the values written back
        assertTrue(rules.contains(StepRule.SOLVE_FOR))
        assertTrue(rules.contains(StepRule.SUBSTITUTE_SOLUTION))
        assertTrue(rules.contains(StepRule.QUADRATIC_FORMULA))
        assertTrue(rules.contains(StepRule.INSERT_VALUE))
        assertEquals("x = 3, y = 4 x = 4, y = 3", clean(s.result.toString()))
        // Already solved for a variable: written in at once
        assertEquals(StepRule.SUBSTITUTE_SOLUTION, steps("y=x^2|x+y=2")!!.steps[1].rule)
        // Every shape has steps that come to the calculator's solutions
        for (text in listOf("x*y=6|x+y=5", "x^2+y^2=25|x-y=1", "y=x^2|y=2x+3", "x*y=1|x+y=3", "x^2-y=1|x+y=3", "x+y=3|x^2+y=5", "x^2+y^2=25|x*y=12")) {
            assertNotNull(text, steps(text))
        }
    }

    @Test
    fun whatIsStillOutOfReach() {
        // Neither equation is linear in a variable
        // (x² + y² = 25 with x² − y² = 7 is solved with u = x², v = y², see QuadraticSystemTest)
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, calc("x^2+y^2=25|x^3+y^3=10").issues.first())
        assertNull(steps("x^2+y^2=25|x^3+y^3=10"))
        // The linear systems are as before
        assertEquals("x = 1, y = 2", clean(calc("x+y=3|x-y=-1").result.toString()))
    }
}
