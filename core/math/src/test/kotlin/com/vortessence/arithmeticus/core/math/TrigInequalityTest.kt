package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** sin(x) > 1/2 and its kind: an interval in every period. */
class TrigInequalityTest {
    private fun calc(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun result(text: String, context: MathContext = MathContext()): String {
        val r = calc(text, context)
        assertTrue(text, r.isSuccess)
        return clean(r.result.toString())
    }

    @Test
    fun sineCosineAndTangentInOnePeriod() {
        assertEquals("(π)/(6) + 2πk < x < (5π)/(6) + 2πk, k ∈ ℤ", result("sin(x)>[1;2]"))
        assertEquals("(5π)/(6) + 2πk ≤ x ≤ (13π)/(6) + 2πk, k ∈ ℤ", result("sin(x)<=[1;2]"))
        assertEquals("−(π)/(2) + 2πk < x < (π)/(2) + 2πk, k ∈ ℤ", result("cos(x)>0"))
        assertEquals("(π)/(3) + 2πk < x < (5π)/(3) + 2πk, k ∈ ℤ", result("cos(x)<[1;2]"))
        assertEquals("(π)/(4) + πk < x < (π)/(2) + πk, k ∈ ℤ", result("tan(x)>1"))
        // The tangent is not defined at π/2: that end stays open
        assertEquals("−(π)/(2) + πk < x ≤ (π)/(4) + πk, k ∈ ℤ", result("tan(x)<=1"))
    }

    @Test
    fun anArgumentAFactorAndTheSidesTheOtherWayRound() {
        assertEquals("(π)/(12) + πk < x < (5π)/(12) + πk, k ∈ ℤ", result("sin(2x)>[1;2]"))
        assertEquals("(π)/(6) + 2πk < x < (5π)/(6) + 2πk, k ∈ ℤ", result("2sin(x)>1"))
        assertEquals("(π)/(6) + 2πk < x < (5π)/(6) + 2πk, k ∈ ℤ", result("[1;2]<sin(x)"))
        // A negative factor turns the relation: −sin x > 1/2 is sin x < −1/2
        assertEquals("(7π)/(6) + 2πk < x < (11π)/(6) + 2πk, k ∈ ℤ", result("-sin(x)>[1;2]"))
    }

    @Test
    fun theEdgesOfTheRange() {
        assertEquals("x = (π)/(2) + 2πk, k ∈ ℤ", result("sin(x)>=1"))
        assertEquals("(π)/(2) + 2πk < x < (5π)/(2) + 2πk, k ∈ ℤ", result("sin(x)<1"))
        assertEquals("x ∈ ℝ", result("sin(x)<=1"))
        assertEquals("x ∈ ℝ", result("cos(x)>=-1"))
        assertEquals("x ∈ ℝ", result("sin(x)<2"))
        for (never in listOf("sin(x)>1", "sin(x)>2", "sin(x)<-1")) {
            assertEquals(never, MathIssue.NO_REAL_SOLUTION, calc(never).issues.first())
        }
    }

    @Test
    fun degreesHaveTheirOwnPeriod() {
        val degrees = MathContext(angleUnit = AngleUnit.DEGREES)
        assertEquals("30 + 360k < x < 150 + 360k, k ∈ ℤ", result("sin(x)>[1;2]", degrees))
        assertEquals("30 + 120k ≤ x ≤ 90 + 120k, k ∈ ℤ", result("cos(3x)<=0", degrees))
    }

    @Test
    fun stepsFromOnePeriodToEveryOne() {
        val plain = steps("sin(x)>[1;2]")!!
        assertEquals(listOf(StepRule.TRIG_INEQUALITY_PERIOD), plain.steps.drop(1).map { it.rule })
        assertTrue(plain.steps[1].notes.any { clean(it.toString()).contains("asin((1)/(2)) = (π)/(6)") })
        // A factor and an inside to solve for x
        val longer = steps("2sin(3x)<=1")!!
        assertEquals(
            listOf(StepRule.TRIG_INEQUALITY_ISOLATE, StepRule.TRIG_INEQUALITY_PERIOD, StepRule.TRIG_INEQUALITY_SOLVE_X),
            longer.steps.drop(1).map { it.rule }
        )
        assertEquals("(5π)/(18) + (2πk)/(3) ≤ x ≤ (13π)/(18) + (2πk)/(3), k ∈ ℤ", clean(longer.result.toString()))
        assertEquals(listOf(StepRule.TRIG_INEQUALITY_BOUNDED), steps("sin(x)<=1")!!.steps.drop(1).map { it.rule })
        // No steps for what has no solution (the calculator says so)
        assertNull(steps("sin(x)>2"))
    }

    @Test
    fun otherInequalitiesAreAsBefore() {
        assertEquals("x > 2", result("x+1>3"))
        // Two different trigonometric functions, or the variable outside them: still out of reach
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, calc("sin(x)>cos(x)").issues.first())
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, calc("sin(x)>x").issues.first())
    }
}
