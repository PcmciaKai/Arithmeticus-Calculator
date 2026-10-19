package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Inequalities one under the other: all of them must hold. */
class InequalitySystemTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun result(text: String): String {
        val r = calc(text)
        assertTrue(text, r.isSuccess)
        return r.result.toString().replace(Regex("\\s+"), " ").replace("− ", "−").trim()
    }

    @Test
    fun theSolutionsHaveToHoldTogether() {
        assertEquals("1 < x < 5", result("x>1|x<5"))
        assertEquals("x > 3", result("x>1|x>3"))
        assertEquals("x < −2", result("x^2-4>0|x<0"))
        assertEquals("1 < x < 4", result("2x+1>3|x-4<0|x>=0"))
        assertEquals("−1 ≤ x ≤ 2", result("x^2<=4|x>=-1"))
        // One value that is in both
        assertEquals("x = 2", result("x>=2|x<=2"))
        // Unions of intervals are intersected piece by piece
        assertEquals("x < −2 or 2 < x < 3", result("x^2-4>0|x<3"))
    }

    @Test
    fun noValueHoldsForAll() {
        for (text in listOf("x>5|x<2", "x>=2|x<2")) assertEquals(text, MathIssue.NO_REAL_SOLUTION, calc(text).issues.first())
        // Two variables are out of reach
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, calc("x+y>1|x-y>0").issues.first())
    }

    @Test
    fun stepsSolveEachThenPutTheSolutionsTogether() {
        val s = steps("2x+1>3|x-4<0|x>=0")!!
        val rules = s.steps.map { it.rule }
        assertEquals(2, rules.count { it == StepRule.INEQUALITY_SYSTEM_EACH })
        assertEquals(StepRule.INEQUALITY_SYSTEM_INTERSECT, rules.last())
        assertTrue(rules.contains(StepRule.COLLECT_SOLUTION))
        assertEquals("1 < x < 4", s.result.toString().replace(Regex("\\s+"), " ").trim())
        // The number line of the result
        assertNotNull(s.numberLine)
        // Already solved: only the intersection
        assertEquals(listOf(StepRule.INEQUALITY_SYSTEM_INTERSECT), steps("x>1|x<5")!!.steps.drop(1).map { it.rule })
        assertNull(steps("x>5|x<2"))
    }
}
