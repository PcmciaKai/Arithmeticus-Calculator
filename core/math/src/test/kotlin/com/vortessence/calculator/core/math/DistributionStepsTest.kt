package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DistributionStepsTest {

    private fun call(name: String, vararg arguments: String): MathString {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, text ->
            if (i > 0) argument.add(SeparatorNode)
            argument.addAll(MathText.fromPlainText(text)?.nodes ?: listOf(VariableNode(text)))
        }
        return MathString(listOf(FunctionNode(name, MathString(argument))))
    }

    private fun solution(name: String, vararg arguments: String) =
        runBlocking { MathEngine.steps(call(name, *arguments), MathContext()) }

    private fun rules(name: String, vararg arguments: String) =
        (solution(name, *arguments) ?: throw AssertionError("No steps for $name(${arguments.joinToString()})")).steps.mapNotNull { it.rule }

    /** The steps end with what the calculator shows. */
    private fun assertEndsWithTheResult(name: String, vararg arguments: String) {
        val steps = solution(name, *arguments)!!
        val calculated = runBlocking { MathEngine.calculate(call(name, *arguments), MathContext()) }
        assertEquals(calculated.result.toString(), steps.result.toString())
    }

    @Test
    fun normalDistribution() {
        assertEquals(StepRule.NORMAL_PROBABILITY, rules("normalcdf", "1.96").first())
        assertEndsWithTheResult("normalcdf", "1.96")
        // The values are put in, and the difference and the quotient are calculated
        val withValues = rules("normalcdf", "110", "100", "15")
        assertEquals(StepRule.NORMAL_PROBABILITY, withValues.first())
        assertTrue(withValues.toString(), StepRule.SUBTRACT_NUMBERS in withValues)
        assertEndsWithTheResult("normalcdf", "110", "100", "15")
        assertEquals(StepRule.NORMAL_DENSITY, rules("normalpdf", "1").first())
        assertEquals(StepRule.NORMAL_DENSITY, rules("normalpdf", "1", "0", "2").first())
        assertEndsWithTheResult("normalpdf", "1", "0", "2")
        // The standard quantile has nothing to scale
        assertNull(solution("normalinv", "0.975"))
        assertEquals(listOf(StepRule.NORMAL_QUANTILE), rules("normalinv", "0.975", "100", "15"))
    }

    @Test
    fun binomialProbability() {
        val rules = rules("binompdf", "10", "0.5", "5")
        assertEquals(listOf(StepRule.BINOMIAL_PROBABILITY, StepRule.BINOMIAL_COEFFICIENT), rules.take(2))
        assertEndsWithTheResult("binompdf", "10", "0.5", "5")
        assertEquals("(63)/(256)", solution("binompdf", "10", "0.5", "5")!!.result.toString())
    }

    @Test
    fun cumulativeProbabilitiesAreSums() {
        // Short: the sum is written out and added up
        val short = rules("binomcdf", "10", "0.5", "2")
        assertEquals(listOf(StepRule.BINOMIAL_CUMULATIVE, StepRule.WRITE_OUT_SUM), short.take(2))
        assertEndsWithTheResult("binomcdf", "10", "0.5", "2")
        assertEquals("(7)/(128)", solution("binomcdf", "10", "0.5", "2")!!.result.toString())
        // Long: the sum, then the result
        assertEquals(listOf(StepRule.BINOMIAL_CUMULATIVE, StepRule.SIMPLIFY), rules("binomcdf", "10", "0.5", "8"))
        assertEndsWithTheResult("binomcdf", "10", "0.5", "8")
        assertEquals(listOf(StepRule.POISSON_CUMULATIVE, StepRule.WRITE_OUT_SUM), rules("poissoncdf", "2", "3").take(2))
        assertEndsWithTheResult("poissoncdf", "2", "3")
        assertEquals(listOf(StepRule.POISSON_CUMULATIVE, StepRule.SIMPLIFY), rules("poissoncdf", "2", "12"))
        assertEndsWithTheResult("poissoncdf", "2", "12")
    }

    @Test
    fun poissonProbability() {
        val rules = rules("poissonpdf", "2", "3")
        assertEquals(listOf(StepRule.POISSON_PROBABILITY, StepRule.FACTORIAL), rules.take(2))
        assertEndsWithTheResult("poissonpdf", "2", "3")
    }

    @Test
    fun noStepsWhereThereIsNothingToShow() {
        // Certain or impossible
        assertNull(solution("binompdf", "5", "0", "2"))
        assertNull(solution("binompdf", "5", "1", "5"))
        assertNull(solution("binompdf", "5", "0.5", "6"))
        assertNull(solution("poissonpdf", "0", "3"))
        // Too many trials for exact fractions, a mean with a variable, and mistakes
        assertNull(solution("binompdf", "1000", "0.5", "500"))
        assertNull(solution("poissonpdf", "l", "3"))
        assertNull(solution("binompdf", "5", "1.5", "2"))
        assertNull(solution("normalcdf", "1", "0"))
    }

    @Test
    fun theStepsAreOffered() {
        assertTrue(runBlocking { MathEngine.hasSteps(call("normalcdf", "1.96"), MathContext()) })
        assertTrue(runBlocking { MathEngine.hasSteps(call("binompdf", "10", "0.5", "5"), MathContext()) })
        assertFalse(runBlocking { MathEngine.hasSteps(call("binompdf", "5", "0", "2"), MathContext()) })
    }
}
