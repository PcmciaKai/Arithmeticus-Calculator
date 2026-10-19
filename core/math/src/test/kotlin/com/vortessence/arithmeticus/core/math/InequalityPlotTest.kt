package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.solvers.Function1D
import com.vortessence.arithmeticus.core.math.solvers.ImplicitCurve
import com.vortessence.arithmeticus.core.math.solvers.InequalityPlot
import com.vortessence.arithmeticus.core.math.solvers.PlotCurve
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The shading of an inequality in the graph. */
class InequalityPlotTest {
    private fun curve(text: String): PlotCurve? = runBlocking { MathEngine.createPlotCurve(TestInput.parse(text), MathContext()) }

    private fun plot(text: String) = curve(text) as InequalityPlot

    @Test
    fun oneVariableShadesTheXAxisInBands() {
        val p = plot("x^2-4>0")
        assertFalse(p.twoVariables)
        // The curve y = x² − 4 is the boundary the tools work on
        assertTrue(p.primary is Function1D)
        assertTrue(p.holds(3.0, 0.0))
        assertTrue(p.holds(-3.0, 0.0))
        assertFalse(p.holds(0.0, 0.0))
        // Strict: the boundary is dashed, and x = 2 itself does not hold
        assertTrue(p.strict)
        assertFalse(p.holds(2.0, 0.0))
        assertTrue(plot("x^2-4>=0").holds(2.0, 0.0))
        assertFalse(plot("x^2-4>=0").strict)
    }

    @Test
    fun twoVariablesShadeThePlane() {
        // y > x²: the function is the boundary
        val above = plot("y>x^2")
        assertTrue(above.twoVariables)
        assertTrue(above.primary is Function1D)
        assertTrue(above.holds(1.0, 2.0))
        assertFalse(above.holds(1.0, 0.5))
        // x² + y² < 1: an implicit curve is the boundary, the inside is shaded
        val disc = plot("x^2+y^2<1")
        assertTrue(disc.primary is ImplicitCurve)
        assertTrue(disc.holds(0.0, 0.0))
        assertFalse(disc.holds(1.0, 1.0))
        // The grid: top row first
        val cells = disc.shade(-2.0, 2.0, -2.0, 2.0, 4, 4)
        assertEquals(listOf(false, false, false, false, false, true, true, false, false, true, true, false, false, false, false, false), cells.toList())
    }

    @Test
    fun anInequalityInYAloneIsAHalfPlane() {
        val below = plot("y<4")
        assertTrue(below.twoVariables)
        assertTrue(below.primary is ImplicitCurve)
        assertTrue(below.holds(100.0, 3.0))
        assertTrue(below.holds(-100.0, 3.0))
        assertFalse(below.holds(0.0, 5.0))
        // Another letter is still the variable of the horizontal axis
        assertFalse(plot("t<4").twoVariables)
    }

    @Test
    fun severalInequalitiesHoldTogether() {
        val band = plot("x>1|x<5")
        assertEquals(2, band.constraints.size)
        // No single boundary curve: the tools are off
        assertNull(band.primary)
        assertTrue(band.holds(3.0, 0.0))
        assertFalse(band.holds(0.0, 0.0))
        assertFalse(band.holds(6.0, 0.0))
    }

    @Test
    fun whatIsStillPlottedAsBefore() {
        assertTrue(curve("x^2-4") is Function1D)
        assertTrue(curve("y=x^2") is Function1D)
        assertNull(curve("x+y+z>1"))
        assertNull(curve("a+b>1"))
    }
}
