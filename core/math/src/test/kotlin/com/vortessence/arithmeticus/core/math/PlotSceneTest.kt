package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.solvers.*
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Several lines as several curves, and where the curves cross. */
class PlotSceneTest {

    private fun scene(text: String, context: MathContext = MathContext()): PlotScene =
        runBlocking { MathEngine.createPlotScene(TestInput.parse(text), context) }

    private fun crossings(
        text: String,
        range: List<Double> = listOf(-10.0, 10.0, -10.0, 10.0),
        context: MathContext = MathContext()
    ): List<PlotIntersection> {
        val s = scene(text, context)
        return runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, range[0], range[1], range[2], range[3]) }
    }

    private fun assertPoint(expectedX: Double, expectedY: Double, found: PlotIntersection, tolerance: Double = 1e-6) {
        assertEquals("x", expectedX, found.x, tolerance)
        assertEquals("y", expectedY, found.y, tolerance)
    }

    @Test
    fun everyLineIsACurve() {
        val s = scene("y = x^2 | y = 2*x + 1 | x^2 + y^2 = 4")
        assertEquals(3, s.entries.size)
        assertTrue(s.entries[0].curve is Function1D)
        assertTrue(s.entries[1].curve is Function1D)
        assertTrue(s.entries[2].curve is ImplicitCurve)
        assertTrue(s.entries.none { it.isDefinition })
    }

    @Test
    fun aSingleLineIsASceneOfOne() {
        val s = scene("sin(x)")
        assertEquals(1, s.entries.size)
        assertTrue(s.entries.single().curve is Function1D)
        assertTrue(scene("").entries.isEmpty())
    }

    @Test
    fun aParabolaAndALineCrossTwice() {
        val found = crossings("y = x^2 | y = 2*x + 1")
        assertEquals(2, found.size)
        val root = sqrt(2.0)
        assertPoint(1 - root, (1 - root) * (1 - root), found[0])
        assertPoint(1 + root, (1 + root) * (1 + root), found[1])
        assertTrue(found.all { it.first == 0 && it.second == 1 })
    }

    @Test
    fun aLineAndACircle() {
        val found = crossings("x^2 + y^2 = 4 | y = x")
        assertEquals(2, found.size)
        val r = sqrt(2.0)
        assertPoint(-r, -r, found[0])
        assertPoint(r, r, found[1])
        // The pair is in the order of the lines whichever kind of curve comes first
        assertTrue(found.all { it.first == 0 && it.second == 1 })
    }

    @Test
    fun twoImplicitCurves() {
        val found = crossings("x^2 + y^2 = 4 | x^2 - y^2 = 1")
        assertEquals(4, found.size)
        val x = sqrt(2.5)
        val y = sqrt(1.5)
        assertEquals(2, found.count { it.x > 0 })
        assertTrue(found.all { abs(abs(it.x) - x) < 1e-6 && abs(abs(it.y) - y) < 1e-6 })
        assertEquals(2, found.count { it.y > 0 })
    }

    @Test
    fun parallelLinesNeverCross() {
        assertTrue(crossings("y = x | y = x + 1").isEmpty())
    }

    @Test
    fun onlyThePointsInTheRangeAreFound() {
        val found = crossings("y = x^2 | y = 2*x + 1", listOf(0.0, 10.0, -10.0, 10.0))
        assertEquals(1, found.size)
        assertEquals(1 + sqrt(2.0), found.single().x, 1e-6)
        // The y range counts too
        assertTrue(crossings("y = x^2 | y = 2*x + 1", listOf(-10.0, 10.0, 0.0, 1.0)).size == 1)
    }

    @Test
    fun aDefinitionOnALineGivesTheOthersAValue() {
        // TestInput has no ":=": the definition is built from nodes
        val definition = listOf(VariableNode("a"), OperatorNode(MathOp.DEFINE), NumberNode("2"), LineBreakNode)
        val s = runBlocking { MathEngine.createPlotScene(MathString(definition + TestInput.parse("y = a*x | y = x + 1").nodes), MathContext()) }
        assertEquals(3, s.entries.size)
        assertTrue(s.entries[0].isDefinition)
        assertEquals("a", s.entries[0].definedVariable)
        assertNull(s.entries[0].curve)
        val found = runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }
        assertEquals(1, found.size)
        assertPoint(1.0, 2.0, found.single())
        // The curves are the lines after the definition
        assertEquals(1, found.single().first)
        assertEquals(2, found.single().second)
    }

    @Test
    fun definitionsOfTheContextApplyToEveryLine() {
        val context = MathContext(variables = mapOf("k" to TestInput.parse("3")))
        val found = crossings("y = k | y = x", context = context)
        assertEquals(1, found.size)
        assertPoint(3.0, 3.0, found.single())
    }

    @Test
    fun aLineThatCannotBeGraphedDoesNotSpoilTheOthers() {
        val s = scene("y = x | x + y + z = 1 | y = 2 - x")
        assertEquals(3, s.entries.size)
        assertNull(s.entries[1].curve)
        val found = runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }
        assertEquals(1, found.size)
        assertPoint(1.0, 1.0, found.single())
        // The indices are those of the entries, not of the curves that could be drawn
        assertEquals(0, found.single().first)
        assertEquals(2, found.single().second)
    }

    @Test
    fun everyPairOfThreeCurvesIsLookedAt() {
        // Three lines through (1, 1): y = x, y = 2 − x, y = 1
        val found = crossings("y = x | y = 2 - x | y = 1")
        assertEquals(3, found.size)
        assertEquals(setOf(0 to 1, 0 to 2, 1 to 2), found.map { it.first to it.second }.toSet())
        assertTrue(found.all { abs(it.x - 1.0) < 1e-6 && abs(it.y - 1.0) < 1e-6 })
    }

    @Test
    fun theBoundaryOfAnInequalityCrossesLikeACurve() {
        val s = scene("y > x^2 | y = 2*x")
        assertTrue(s.entries[0].curve is InequalityPlot)
        val found = runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }
        assertEquals(2, found.size)
        assertPoint(0.0, 0.0, found[0])
        assertPoint(2.0, 4.0, found[1])
    }

    @Test
    fun aSurfaceTakesPartInNoCrossing() {
        val s = scene("z = x^2 + y^2 | y = x")
        assertNotNull(s.surfaceEntry)
        val found = runBlocking { MathEngine.findIntersections(s.entries.map { it.curve }, -10.0, 10.0, -10.0, 10.0) }
        assertTrue(found.isEmpty())
    }

    @Test
    fun sinesCrossAtEveryOtherPoint() {
        val found = crossings("y = sin(x) | y = cos(x)", listOf(0.0, 7.0, -2.0, 2.0))
        assertEquals(2, found.size)
        assertEquals(Math.PI / 4, found[0].x, 1e-6)
        assertEquals(5 * Math.PI / 4, found[1].x, 1e-6)
    }

    @Test
    fun theSameCurveWrittenTwiceHasNoCrossings() {
        assertTrue(crossings("x^2 + y^2 = 4 | x^2 + y^2 = 4").isEmpty())
        assertTrue(crossings("x^2 + y^2 = 4 | 2*x^2 + 2*y^2 = 8").isEmpty())
        assertTrue(crossings("y = x^2 | y = x^2").isEmpty())
    }

    @Test
    fun theBoundaryOfAnInequalityInOneVariableIsNoCurveToCross() {
        // x² > 1 has the boundary x = ±1; the parabola drawn for it is no boundary
        assertTrue(crossings("x^2 > 1 | y = 3").isEmpty())
    }

    @Test
    fun aCurveInSeveralPairsGivesTheSameCrossingsAsInPairsAlone() {
        val three = crossings("x^2 + y^2 = 4 | x^2 - y^2 = 1 | y = x")
        // The circle and the hyperbola cross four times, the circle and the line twice, the hyperbola and y = x never
        assertEquals(6, three.size)
        assertEquals(4, three.count { it.first == 0 && it.second == 1 })
        assertEquals(2, three.count { it.first == 0 && it.second == 2 })
    }

    @Test
    fun linesSplitLikeTheParserDoes() {
        val lines = TestInput.parse("y = x | y = 2 | ").lines()
        assertEquals(3, lines.size)
        assertEquals(1, TestInput.parse("y = x").lines().size)
        assertTrue(MathString().lines().single().isEmpty)
    }

    @Test
    fun severalInequalitiesHaveACommonRegion() {
        val band = scene("x > 1 | x < 5").commonRegion()!!
        assertEquals(2, band.constraints.size)
        assertFalse(band.twoVariables)
        assertTrue(band.holds(3.0, 0.0))
        assertFalse(band.holds(6.0, 0.0))
        assertFalse(band.holds(0.0, 0.0))

        val plane = scene("y > x^2 | y < 4 | x > 0").commonRegion()!!
        assertTrue(plane.twoVariables)
        assertTrue(plane.holds(1.0, 2.0))
        assertFalse(plane.holds(-1.0, 2.0))
        assertFalse(plane.holds(1.0, 5.0))
    }

    @Test
    fun aSingleInequalityHasNoCommonRegion() {
        assertNull(scene("y > x^2").commonRegion())
        // Equations take no part in it
        assertNull(scene("y > x^2 | y = 2*x").commonRegion())
        // Hidden lines do not count
        assertNull(scene("x > 1 | x < 5").commonRegion(hidden = setOf(1)))
        assertNotNull(scene("x > 1 | x < 5 | y > 2").commonRegion(hidden = setOf(2)))
    }
}
