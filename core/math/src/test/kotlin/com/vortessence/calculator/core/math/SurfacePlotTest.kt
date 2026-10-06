package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.ImplicitCurve
import com.vortessence.calculator.core.math.solvers.PlotCurve
import com.vortessence.calculator.core.math.solvers.Surface3D
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SurfacePlotTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val y = VariableNode("y")
    private val z = VariableNode("z")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg e: MathNode) = ExponentNode(null, ms(*e))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))

    private fun curve(vararg nodes: MathNode): PlotCurve? = runBlocking { MathEngine.createPlotCurve(ms(*nodes), MathContext()) }

    @Test
    fun expressionsInXAndYAreSurfaces() {
        val surface = curve(f("sin", x), f("cos", y)) as Surface3D
        assertEquals(sin(1.0) * cos(2.0), surface.evaluateAt(1.0, 2.0), 1e-12)
        assertTrue(curve(x, pow(n("2")), op(MathOp.MINUS), y, pow(n("2"))) is Surface3D)
    }

    @Test
    fun equationsForZAreSurfaces() {
        val surface = curve(z, op(MathOp.EQUALS), x, op(MathOp.PLUS), y) as Surface3D
        assertEquals(3.0, surface.evaluateAt(1.0, 2.0), 1e-12)
        assertTrue(curve(x, y, op(MathOp.EQUALS), z) is Surface3D)
        // z = f(x) is a surface too (constant along y)
        assertTrue(curve(z, op(MathOp.EQUALS), x, pow(n("2"))) is Surface3D)
    }

    @Test
    fun otherInputsAreUnchanged() {
        assertTrue(curve(x, pow(n("2"))) is Function1D)
        assertTrue(curve(y, op(MathOp.EQUALS), x, pow(n("2"))) is Function1D)
        // An equation in x and y stays an implicit curve
        assertTrue(curve(x, pow(n("2")), op(MathOp.PLUS), y, pow(n("2")), op(MathOp.EQUALS), n("1")) is ImplicitCurve)
        // Three variables: nothing to plot
        assertNull(curve(x, y, z))
    }
}
