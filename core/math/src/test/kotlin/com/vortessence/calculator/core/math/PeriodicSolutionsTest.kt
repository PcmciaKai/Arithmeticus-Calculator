package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A trigonometric equation is answered with its general solution, not with the few solutions that were found. */
class PeriodicSolutionsTest {

    private fun solve(text: String, angle: AngleUnit = AngleUnit.RADIANS, mode: CalculationMode = CalculationMode.EXACT) =
        runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext(angleUnit = angle, mode = mode)) }

    private fun answer(text: String, angle: AngleUnit = AngleUnit.RADIANS) =
        solve(text, angle).result.toString().trim().replace(Regex("\\s+"), " ")

    @Test
    fun sineCosineAndTangentGiveTheirGeneralSolutions() {
        assertEquals("x = (π)/(6) + 2πk, x = (5π)/(6) + 2πk, k ∈ ℤ", answer("sin(x)=1/2"))
        assertEquals("x = (π)/(3) + 2πk, x = − (π)/(3) + 2πk, k ∈ ℤ", answer("cos(x)=1/2"))
        assertEquals("x = (π)/(4) + πk, k ∈ ℤ", answer("tan(x)=1"))
        assertEquals("x = πk, k ∈ ℤ", answer("sin(x)=0"))
    }

    @Test
    fun theReciprocalFunctionsToo() {
        assertEquals("x = (π)/(3) + 2πk, x = − (π)/(3) + 2πk, k ∈ ℤ", answer("sec(x)=2"))
        assertEquals("x = (π)/(6) + 2πk, x = (5π)/(6) + 2πk, k ∈ ℤ", answer("csc(x)=2"))
        assertEquals("x = (π)/(4) + πk, k ∈ ℤ", answer("cot(x)=1"))
    }

    @Test
    fun inDegreesTheFamiliesAreInDegrees() {
        assertEquals("x = 30 + 360k, x = 150 + 360k, k ∈ ℤ", answer("sin(x)=1/2", AngleUnit.DEGREES))
        assertEquals("x = 120k, k ∈ ℤ", answer("cos(2x)=cos(x)", AngleUnit.DEGREES))
    }

    @Test
    fun equationsWithSeveralFactorsAndInnerFunctions() {
        assertEquals("x = (π)/(2) + 2πk, k ∈ ℤ", answer("sin(x)^2+sin(x)=2"))
        assertEquals("x = (πk)/(2), k ∈ ℤ", answer("sin(2*x)=0"))
    }

    @Test
    fun equationsThatHaveNoGeneralSolutionKeepTheirNumericOnes() {
        // sin(x) = x/2 is only solved by approximation: no family to show
        val result = answer("sin(x)=[x;2]")
        assertFalse(result, result.contains("ℤ"))
        assertTrue(result, result.contains("x = "))
        // Decimal mode and equations without trigonometry are not touched
        assertFalse(solve("sin(x)=1/2", mode = CalculationMode.NUMERIC).result.toString().contains("ℤ"))
        assertEquals("x = 3", answer("x+1=4"))
    }

    @Test
    fun theResultIsStillAnAnswerForTheNextCalculation() {
        // The value that Ans holds is the first solution found, as before
        val res = solve("sin(x)=1/2")
        assertEquals(-9.948376736, res.numericValue, 1e-6)
    }
}
