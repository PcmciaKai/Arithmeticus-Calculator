package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** x·|x| = 4: by the sign of the inside of |…|. */
class AbsoluteSplitTest {
    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    @Test
    fun theTwoCasesAndWhatCounts() {
        val s = steps("x*abs(x)=4")!!
        val rules = s.steps.map { it.rule }
        assertEquals(StepRule.ABSOLUTE_VALUE_SPLIT, rules[1])
        assertEquals(2, rules.count { it == StepRule.ABSOLUTE_VALUE_CASE })
        assertEquals("x = 2", clean(s.result.toString()))
        // x ≥ 0 gives ±2 and only 2 counts, the other case has no real solution
        val checks = s.steps.flatMap { it.notes }.map { clean(it.toString()) }
        assertTrue(checks.any { it.contains("x = 2") && it.contains("✓") })
        assertTrue(checks.any { it.contains("x = −2") && it.contains("✗") })
        assertTrue(rules.contains(StepRule.NO_REAL_SOLUTION))
    }

    @Test
    fun morePartsAroundTheAbsoluteValue() {
        assertEquals("x = 2", clean(steps("x*abs(x)+x=6")!!.result.toString()))
        assertEquals("x = −2", clean(steps("abs(x)*x=-4")!!.result.toString()))
        assertEquals("x = 2", clean(steps("x*abs(x-1)=2")!!.result.toString()))
        // An absolute value that can be isolated has its own rule
        assertFalse(steps("x^2+abs(x)=6")!!.steps.any { it.rule == StepRule.ABSOLUTE_VALUE_SPLIT })
    }
}
