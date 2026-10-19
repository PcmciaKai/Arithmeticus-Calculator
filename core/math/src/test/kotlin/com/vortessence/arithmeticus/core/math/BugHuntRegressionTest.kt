package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.type.LineBreakNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Mistakes found by hunting for bugs, each with its example. */
class BugHuntRegressionTest {
    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun result(text: String): String {
        val r = calc(text)
        assertTrue(text, r.isSuccess)
        val parts = mutableListOf(StringBuilder())
        for (node in r.result.nodes) if (node === LineBreakNode) parts.add(StringBuilder()) else parts.last().append(MathStringText.of(node))
        return parts.joinToString(" | ") { clean(it.toString()) }
    }

    @Test
    fun theNumericSearchFindsRootsFarFromZeroAsWell() {
        // The search near 0 found 4 and 3.5 and stopped; the other root was 25 and 34.5
        assertEquals("x = 4, x = 25", result("x+[100;x]=29"))
        assertEquals("x = 4, x = 25", result("[x^2-29x+100;x]=0"))
        assertEquals("0 < x < 4 or x > 25", result("[x^2-29x+100;x]>0"))
        assertEquals("x = 19 −sqrt(241), x = 19 + sqrt(241)", result("[1;x]+[1;x-30]=[1;4]"))
    }

    @Test
    fun aSimpleFractionIsShownAsOne() {
        // 1.5 was shown for 3/2 where the equation is only solved numerically, and 3/2 in its steps
        assertEquals("x = −(3)/(2), x = 0, x = (3)/(2)", result("2x*abs(x)-3x=0"))
    }

    @Test
    fun aLineWithoutTheVariableIsTrueOrFalseForAll() {
        assertEquals("x > 1", result("x>1|1<2"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, calc("x>1|2<1").issues.first())
    }

    @Test
    fun conjugateStepsAlsoWithAConstant() {
        // √(9x² − x) − 3x − 1: the terms 3x and 1 are one polynomial
        for (text in listOf("L[x->inf]{sqrt(9x^2-x)-3x-1}", "L[x->inf]{sqrt(4x^2+x-3)-2x-2}", "L[x->-inf]{sqrt(9x^2+x+3)+3x+2}")) {
            val s = steps(text)
            assertNotNull(text, s)
            assertEquals(text, clean(calc(text).result.toString()), clean(s!!.result.toString()))
        }
    }

    @Test
    fun whereTheCoefficientIsZeroTheOtherSolutionsAreStillThere() {
        // x·y = 0 was solved by dividing by x: x = 0 was lost
        assertEquals("x = −1, y = 0 | x = 0, y = −1 | x = 0, y = 1 | x = 1, y = 0", result("x*y=0|x^2+y^2=1"))
        assertEquals("x = −2, y = 1 | x = 0, y = −sqrt(5) | x = 0, y = sqrt(5) | x = 2, y = 1", result("x*y=x|x^2+y^2=5"))
        assertEquals("x = −2sqrt(2), y = 0 | x = 1, y = −sqrt(7) | x = 1, y = sqrt(7) | x = 2sqrt(2), y = 0", result("x*y=y|x^2+y^2=8"))
        // And where it cannot be zero, the same as before
        assertEquals("x = 2, y = 3 | x = 3, y = 2", result("x*y=6|x+y=5"))
    }
}
