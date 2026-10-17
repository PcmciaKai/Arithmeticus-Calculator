package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.solvers.ImplicitCurve
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Lines in vector form, g = (1, 2) + r·(2, 1): where two of them meet, as numbers, step by step and in the graph. */
class VectorLineTest {

    // g = (1, 2) + r·(2, 1) and h = (−1, 5) + s·(1, −1) meet where r = 1/3 and s = 8/3, in (5/3, 7/3)
    private val g = "M[1/2] + r*M[2/1]"
    private val h = "M[-1/5] + s*M[1/-1]"

    private fun calc(text: String, context: MathContext = MathContext()) =
        runBlocking { MathEngine.calculate(TestInput.parse(text), context) }

    private fun steps(text: String, context: MathContext = MathContext()) =
        runBlocking { MathEngine.steps(TestInput.parse(text), context) }

    private fun scene(text: String, context: MathContext = MathContext()) =
        runBlocking { MathEngine.createPlotScene(TestInput.parse(text), context) }

    /** The text of the components of the vector in the result. */
    private fun pointOf(result: MathString): List<String> =
        result.nodes.filterIsInstance<MatrixNode>().single().elements.map { it.toString().replace(" ", "") }

    @Test
    fun twoLinesMeetInAPoint() {
        val res = calc("$g = $h")
        assertTrue("${res.issues}", res.isSuccess)
        val text = res.result.toString().replace(" ", "")
        assertTrue(text, text.startsWith("r=(1)/(3),s=(8)/(3),S="))
        assertEquals(listOf("(5)/(3)", "(7)/(3)"), pointOf(res.result))
        // The point is what can be reused
        val answer = runBlocking { MathEngine.calculate(res.answer!!, MathContext()) }
        assertEquals(listOf(5.0 / 3, 7.0 / 3), (answer.expression as MatrixExpression).elements.map { it.evaluateNumerically() })
    }

    @Test
    fun theOrderOfTheLinesDoesNotMatter() {
        val flipped = calc("$h = $g")
        assertTrue("${flipped.issues}", flipped.isSuccess)
        assertTrue(flipped.result.toString().replace(" ", "").startsWith("r=(1)/(3),s=(8)/(3),S="))
        assertEquals(listOf("(5)/(3)", "(7)/(3)"), pointOf(flipped.result))
    }

    @Test
    fun aPointOnALine() {
        // (3, 3) is on g for r = 1: the point is given, so it is not said again
        val res = calc("M[3/3] = $g")
        assertTrue("${res.issues}", res.isSuccess)
        assertEquals("r=1", res.result.toString().replace(" ", ""))
        // (3, 4) is not
        assertEquals(listOf(MathIssue.CONTRADICTION), calc("M[3/4] = $g").issues)
    }

    @Test
    fun parallelLinesNeverMeet() {
        assertEquals(listOf(MathIssue.CONTRADICTION), calc("M[0/0] + r*M[1/1] = M[0/1] + s*M[2/2]").issues)
    }

    @Test
    fun theSameLineTwiceHasNoSinglePoint() {
        val res = calc("M[0/0] + r*M[1/1] = M[1/1] + s*M[2/2]")
        assertTrue("${res.issues}", res.isSuccess)
        assertTrue(res.result.nodes.none { it is MatrixNode })
    }

    @Test
    fun linesInSpace() {
        val meet = calc("M[0/0/0] + r*M[1/1/1] = M[1/1/1] + s*M[1/0/0]")
        assertTrue("${meet.issues}", meet.isSuccess)
        assertEquals(listOf("1", "1", "1"), pointOf(meet.result))
        // Skew: no common point although they are not parallel
        assertEquals(listOf(MathIssue.CONTRADICTION), calc("M[0/0/0] + r*M[1/1/1] = M[1/1/2] + s*M[1/0/0]").issues)
    }

    @Test
    fun vectorsOfDifferentLengthsAreAnError() {
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), calc("M[1/2] + r*M[2/1] = M[1/2/3]").issues)
    }

    @Test
    fun equalVectorsWithoutParametersAreTrueOrFalse() {
        assertEquals("true", calc("M[1/2] = M[1/2]").result.toString())
        assertEquals("false", calc("M[1/2] = M[1/3]").result.toString())
        assertEquals("true", calc("M[1/2] = M[0.5*2/2*1]").result.toString())
    }

    @Test
    fun matricesStillHaveNoEquations() {
        assertEquals(listOf(MathIssue.MATRIX_UNSUPPORTED), calc("M[1 2/3 4] = M[1 2/3 4]").issues)
    }

    @Test
    fun definedLinesMeet() {
        val context = MathContext(variables = linkedMapOf("g" to TestInput.parse(g), "h" to TestInput.parse(h)))
        val res = calc("g = h", context)
        assertTrue("${res.issues}", res.isSuccess)
        assertEquals(listOf("(5)/(3)", "(7)/(3)"), pointOf(res.result))
    }

    @Test
    fun theStepsCompareTheComponentsAndPutTheParameterIntoTheLine() {
        val solution = steps("$g = $h")!!
        val rules = solution.steps.map { it.rule }
        assertEquals(null, rules.first())
        assertEquals(StepRule.COMPARE_COMPONENTS, rules[1])
        assertEquals(listOf(StepRule.INSERT_PARAMETER, StepRule.SIMPLIFY), rules.takeLast(2))
        // The result is the one of the calculation
        assertEquals(calc("$g = $h").result.toString(), solution.result.toString())
        assertTrue(runBlocking { MathEngine.hasSteps(TestInput.parse("$g = $h"), MathContext()) })
    }

    @Test
    fun aVectorWithAParameterIsALineInTheGraph() {
        val entry = scene(g).entries.single()
        val line = entry.curve as ImplicitCurve
        // On the line: (1, 2) for r = 0, (3, 3) for r = 1, (−1, 1) for r = −1
        assertEquals(0.0, line.evaluateAt(1.0, 2.0), 1e-9)
        assertEquals(0.0, line.evaluateAt(3.0, 3.0), 1e-9)
        assertEquals(0.0, line.evaluateAt(-1.0, 1.0), 1e-9)
        assertNotEquals(0.0, line.evaluateAt(0.0, 0.0), 1e-9)
    }

    @Test
    fun theNameOfALineOnlyLabelsIt() {
        val entry = scene("g = $g").entries.single()
        assertEquals(0.0, (entry.curve as ImplicitCurve).evaluateAt(3.0, 3.0), 1e-9)
    }

    @Test
    fun aDefinedLineIsDrawnByItsName() {
        val context = MathContext(variables = linkedMapOf("g" to TestInput.parse(g), "h" to TestInput.parse(h)))
        val s = scene("g | h", context)
        assertTrue(s.entries.all { it.curve is ImplicitCurve })
        assertEquals(1, runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }.size)
    }

    @Test
    fun anExpressionThatOnlyContainsAMatrixIsStillAFunction() {
        // det([x 1; 1 2]) = 2x − 1 and |(x, 1)| are numbers for every x, not vectors
        for (text in listOf("det(M[x 1/1 2])", "y = det(M[x 1/1 2])", "norm(M[x/1])")) {
            assertTrue(text, scene(text).entries.single().curve is com.vortessence.calculator.core.math.solvers.Function1D)
        }
        val det = scene("det(M[x 1/1 2])").entries.single().curve as com.vortessence.calculator.core.math.solvers.Function1D
        assertEquals(5.0, det.evaluateAt(3.0), 1e-9)
    }

    @Test
    fun twoLinesWithTheSameLetterHaveAParameterEach() {
        // g = (1, 2) + r·(2, 1) and h = (−1, 5) + r·(1, −1): the same lines as with r and s
        val same = "M[1/2] + r*M[2/1] = M[-1/5] + r*M[1/-1]"
        val res = calc(same)
        assertTrue("${res.issues}", res.isSuccess)
        assertTrue(res.result.toString(), res.result.toString().replace(" ", "").startsWith("r₁=(1)/(3),r₂=(8)/(3),S="))
        assertEquals(listOf("(5)/(3)", "(7)/(3)"), pointOf(res.result))
        val solution = steps(same)!!
        assertEquals(listOf(StepRule.INSERT_PARAMETER, StepRule.SIMPLIFY), solution.steps.takeLast(2).map { it.rule })
        assertEquals(res.result.toString(), solution.result.toString())
        // Lines that are parallel still never meet
        assertEquals(listOf(MathIssue.CONTRADICTION), calc("M[0/0] + r*M[1/1] = M[0/1] + r*M[2/2]").issues)
    }

    @Test
    fun aVerticalAndAHorizontalLine() {
        val vertical = scene("M[2/0] + r*M[0/1]").entries.single().curve as ImplicitCurve
        assertEquals(0.0, vertical.evaluateAt(2.0, 7.0), 1e-9)
        assertNotEquals(0.0, vertical.evaluateAt(3.0, 7.0), 1e-9)
        val horizontal = scene("M[0/-1] + r*M[3/0]").entries.single().curve as ImplicitCurve
        assertEquals(0.0, horizontal.evaluateAt(9.0, -1.0), 1e-9)
    }

    @Test
    fun whatIsNoLineInThePlaneIsNoCurve() {
        // A point, a line in space, a vector that is not linear in its parameter, a vector with two parameters
        for (text in listOf("M[1/2]", "M[1/2/3] + r*M[1/1/1]", "M[r^2/r]", "M[r/s]", "M[1 2/3 4] + r*M[1 0/0 1]", "M[1/2] + r*M[0/0]")) {
            assertNull(text, scene(text).entries.single().curve)
        }
    }

    @Test
    fun twoLinesMeetingAreBothDrawnAndCross() {
        val s = scene("$g = $h")
        assertEquals(2, s.entries.size)
        assertTrue(s.entries.all { it.curve is ImplicitCurve })
        val crossings = runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }
        assertEquals(1, crossings.size)
        assertEquals(5.0 / 3, crossings.single().x, 1e-6)
        assertEquals(7.0 / 3, crossings.single().y, 1e-6)
    }

    @Test
    fun linesCrossOtherCurves() {
        // g is the line y = x/2 + 3/2, which crosses the parabola twice
        val crossings = runBlocking {
            val s = scene("$g | y = x^2")
            MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0)
        }
        assertEquals(2, crossings.size)
        for (c in crossings) assertEquals(c.x / 2 + 1.5, c.y, 1e-6)
    }

    @Test
    fun parallelLinesDrawnNeverCross() {
        val s = scene("M[0/0] + r*M[1/1] | M[0/1] + s*M[2/2]")
        assertTrue(runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }.isEmpty())
    }
}
