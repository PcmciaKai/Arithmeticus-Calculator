package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The steps of derivatives and integrals that involve gamma and the error function. */
class SpecialStepsTest {

    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun rules(text: String): List<StepRule> =
        (runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) } ?: throw AssertionError("No steps for $text"))
            .steps.mapNotNull { it.rule }

    private fun hasNoSteps(text: String) = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) } == null

    @Test
    fun derivativesOfTheErrorFunctionNameTheRule() {
        assertEquals(listOf(StepRule.ERROR_FUNCTION), rules("D{erf(x)}"))
        assertEquals(listOf(StepRule.COMPLEMENTARY_ERROR_FUNCTION), rules("D{erfc(x)}"))
        assertEquals(StepRule.LAMBERT_W_FUNCTION, rules("D{lambertw(x)}").first())
        assertEquals(listOf(StepRule.SINE_INTEGRAL_FUNCTION), rules("D{sinint(x)}"))
        assertEquals(listOf(StepRule.COSINE_INTEGRAL_FUNCTION), rules("D{cosint(x)}"))
        assertEquals(listOf(StepRule.EXPONENTIAL_INTEGRAL_FUNCTION), rules("D{expint(x)}"))
        val chain = rules("D{erf(x^2)}")
        assertTrue(chain.toString(), StepRule.CHAIN_RULE in chain && StepRule.ERROR_FUNCTION in chain)
    }

    @Test
    fun derivativesOfGammaNameTheRule() {
        assertEquals(listOf(StepRule.GAMMA_FUNCTION), rules("D{gamma(x)}"))
        assertEquals(listOf(StepRule.LOG_GAMMA_FUNCTION), rules("D{lngamma(x)}"))
        val chain = rules("D{gamma(2*x)}")
        assertTrue(chain.toString(), StepRule.CHAIN_RULE in chain && StepRule.GAMMA_FUNCTION in chain)
        // The digamma would need the trigamma: no derivative, so no steps
        assertTrue(hasNoSteps("D{digamma(x)}"))
    }

    @Test
    fun theGaussianIntegralHasASteppedRule() {
        assertEquals(listOf(StepRule.INTEGRAL_GAUSSIAN), rules("I{e^{-x^2}}"))
        val scaled = calc("I{e^{-2*x^2}}")
        assertTrue("${scaled.issues}", scaled.isSuccess)
        assertTrue(StepRule.INTEGRAL_GAUSSIAN in rules("I{e^{-2*x^2}}"))
    }

    @Test
    fun theDefiniteGaussianIntegralsAreCalculated() {
        val unit = calc("I[0,1]{e^{-x^2}}")
        assertTrue("${unit.issues}", unit.isSuccess)
        assertEquals(0.746824132812427, unit.numericValue, 1e-9)
        assertTrue(StepRule.INTEGRAL_GAUSSIAN in rules("I[0,1]{e^{-x^2}}"))
        // The whole line: √π
        val whole = calc("I[-inf,inf]{e^{-x^2}}")
        assertTrue("${whole.issues}", whole.isSuccess)
        assertEquals(Math.sqrt(Math.PI), whole.numericValue, 1e-9)
        val half = calc("I[0,inf]{e^{-x^2}}")
        assertTrue("${half.issues}", half.isSuccess)
        assertEquals(Math.sqrt(Math.PI) / 2, half.numericValue, 1e-9)
    }

    @Test
    fun aCoefficientOfUnknownSignIsNotIntegratedAsAGaussian() {
        // e^(−y·x²) is only a Gaussian for y > 0
        val res = calc("I{e^{-y*x^2}}")
        assertFalse(res.isSuccess && res.result.toString().contains("erf"))
        assertTrue(hasNoSteps("I{e^{-y*x^2}}"))
        // e^(+x²) would need the imaginary error function
        assertFalse(calc("I{e^{x^2}}").isSuccess)
    }

    @Test
    fun theErrorFunctionItselfIsIntegrated() {
        // ∫₀¹ erf(x) dx = erf(1) + (e^(−1) − 1)/√π
        val definite = calc("I[0,1]{erf(x)}")
        assertTrue("${definite.issues}", definite.isSuccess)
        assertEquals(0.8427007929497149 + (Math.exp(-1.0) - 1) / Math.sqrt(Math.PI), definite.numericValue, 1e-9)
        val scaled = calc("I[0,1]{erf(2*x)}")
        assertTrue("${scaled.issues}", scaled.isSuccess)
        // ∫₀¹ erf(2x) dx = (2·erf(2) + (e^(−4) − 1)/√π)/2
        assertEquals((2 * 0.9953222650189527 + (Math.exp(-4.0) - 1) / Math.sqrt(Math.PI)) / 2, scaled.numericValue, 1e-9)
        val complement = calc("I[0,1]{erfc(x)}")
        assertTrue("${complement.issues}", complement.isSuccess)
        assertEquals(1 - (0.8427007929497149 + (Math.exp(-1.0) - 1) / Math.sqrt(Math.PI)), complement.numericValue, 1e-9)
        assertTrue(calc("I{erf(x)}").isSuccess)
        // Not of a linear argument
        assertFalse(calc("I{erf(x^2)}").isSuccess)
    }
}
