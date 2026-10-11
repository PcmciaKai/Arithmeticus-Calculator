package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Conjugate limits at ∞, and one-sided limits with |g| and e^g. */
class LimitGapsTest {
    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun rules(text: String) = steps(text)?.steps?.map { it.rule }

    @Test
    fun conjugateAtInfinity() {
        // √(x² + x) − x → 1/2: multiplied by the conjugate, the roots gone, divided by x
        val s = steps("L[x->inf]{sqrt(x^2+x)-x}")!!
        assertEquals("(1)/(2)", clean(s.result.toString()))
        val rules = s.steps.map { it.rule }
        assertEquals(listOf(StepRule.LIMIT_CONJUGATE, StepRule.LIMIT_DIFFERENCE_OF_SQUARES), rules.drop(1).take(2))
        assertTrue(rules.contains(StepRule.DIVIDE_BY_HIGHEST_POWER))
        assertTrue(rules.contains(StepRule.LIMIT_VANISH))
        // The same for other shapes, and for −∞ (where √(x²)/x is −1)
        assertEquals("0", clean(steps("L[x->inf]{sqrt(x^2+1)-x}")!!.result.toString()))
        assertEquals("1", clean(steps("L[x->inf]{sqrt(x^2+3x)-sqrt(x^2+x)}")!!.result.toString()))
        assertEquals("(1)/(4)", clean(steps("L[x->inf]{sqrt(4x^2+x)-2x}")!!.result.toString()))
        assertEquals("(1)/(2)", clean(steps("L[x->inf]{x-sqrt(x^2-x)}")!!.result.toString()))
        assertEquals("−(1)/(2)", clean(steps("L[x->-inf]{sqrt(x^2+x)+x}")!!.result.toString()))
        assertEquals("(1)/(2)", clean(steps("L[x->inf]{sqrt(x^4+x^2)-x^2}")!!.result.toString()))
        // Not of this kind: ∞ − 1 is simply ∞, without the conjugate
        assertFalse(rules("L[x->inf]{sqrt(x^2+x)-1}").orEmpty().contains(StepRule.LIMIT_CONJUGATE))
    }

    @Test
    fun absoluteValueNextToThePoint() {
        // |x|/x for x → 0⁺: |x| = x, cancelled
        val plus = steps("L[x->0^+]{[abs(x);x]}")!!
        assertEquals("1", clean(plus.result.toString()))
        assertEquals(StepRule.LIMIT_ABSOLUTE_VALUE, plus.steps[1].rule)
        assertTrue(plus.steps[1].notes.any { clean(it.toString()).contains("positive") })
        // For x → 0⁻, |x| = −x: −1
        val minus = steps("L[x->0^-]{[abs(x);x]}")!!
        assertEquals("−1", clean(minus.result.toString()))
        assertTrue(minus.steps[1].notes.any { clean(it.toString()).contains("negative") })
        // At another point
        assertEquals("−1", clean(steps("L[x->3^-]{[abs(x-3);x-3]}")!!.result.toString()))
        // From both sides there is no limit
        assertNull(steps("L[x->0]{[abs(x);x]}"))
    }

    @Test
    fun exponentialWithAnInfiniteExponent() {
        val right = steps("L[x->0^+]{e^{[1;x]}}")!!
        assertEquals(StepRule.LIMIT_EXPONENT_INFINITE, right.steps[1].rule)
        assertEquals("∞", clean(right.result.toString()))
        assertEquals("0", clean(steps("L[x->0^-]{e^{[1;x]}}")!!.result.toString()))
        assertEquals("∞", clean(steps("L[x->2^+]{e^{[1;x-2]}}")!!.result.toString()))
        assertTrue(steps("L[x->0^-]{e^{[1;x]}}")!!.steps[1].notes.any { clean(it.toString()).contains("−∞") })
    }
}
