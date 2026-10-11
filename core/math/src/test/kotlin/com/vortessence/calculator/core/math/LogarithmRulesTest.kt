package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Logarithms of numbers to one base combine: sums, differences, quotients. */
class LogarithmRulesTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun result(text: String) = calc(text).result.toString().replace(Regex("\\s+"), " ").trim()

    @Test
    fun sumsAndDifferencesOfLogarithmsOfNumbers() {
        assertEquals("2", result("log(25)+log(4)"))
        assertEquals("1", result("log(2)+log(5)"))
        assertEquals("1", result("log(50)-log(5)"))
        assertEquals("ln(3)", result("ln(6)-ln(2)"))
        assertEquals("ln(2)", result("3ln(2)-ln(4)"))
        assertEquals("ln(6)", result("ln(2)+ln(3)"))
        assertEquals("ln(30)", result("ln(2)+ln(3)+ln(5)"))
        assertEquals("0", result("2ln(3)-ln(9)"))
        // Letters stay as they are: ln(x) + ln(y) = ln(xy) only for positive x and y
        assertEquals("ln(x) + ln(y)", result("ln(x)+ln(y)"))
    }

    @Test
    fun quotientsOfLogarithmsAreChangeOfBase() {
        assertEquals("3", result("ln(8)/ln(2)"))
        assertEquals("3", result("log(8)/log(2)"))
        assertEquals("(2)/(3)", result("ln(9)/ln(27)"))
        assertEquals("3", result("ln(4)/ln(2)+1"))
        // Not a power of each other: stays
        assertEquals("(ln(5))/(ln(3))", result("ln(5)/ln(3)"))
    }

    @Test
    fun stepsShowTheRulesOfLogarithms() {
        val sum = steps("log(25)+log(4)")!!
        assertEquals(StepRule.LOG_SUM, sum.steps[1].rule)
        assertEquals(listOf(StepRule.LOG_SUM, StepRule.MULTIPLY_NUMBERS, StepRule.LOGARITHM_OF_POWER), sum.steps.drop(1).map { it.rule })
        assertEquals("2", sum.result.toString().trim())
        assertEquals(listOf(StepRule.LOG_SUM, StepRule.DIVIDE_NUMBERS), steps("ln(6)-ln(2)")!!.steps.drop(1).map { it.rule })
        assertEquals(StepRule.LOG_QUOTIENT, steps("ln(8)/ln(2)")!!.steps[1].rule)
        assertEquals("3", steps("ln(8)/ln(2)")!!.result.toString().trim())
    }
}
