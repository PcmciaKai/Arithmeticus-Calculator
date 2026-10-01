package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.PlotPoint
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The curve F(x, y) = 0 of an equation that cannot be written as y = f(x), e.g. x² + y² = 1.
 * [expression] is F, i.e. left side minus right side.
 */
class ImplicitCurve(
    val expression: Expression,
    val angleUnit: AngleUnit = AngleUnit.RADIANS
) : PlotCurve {
    // Only read during evaluation, so one shared instance is safe to use from several threads
    private val evaluationContext = MathContext(mode = CalculationMode.NUMERIC, angleUnit = angleUnit)

    /** Binds x and y without allocating a general-purpose map for every evaluation. */
    private class XYBindings(private val x: Double, private val y: Double) : AbstractMap<Variable, Double>() {
        override val entries: Set<Map.Entry<Variable, Double>>
            get() = setOf(java.util.AbstractMap.SimpleEntry(X, x), java.util.AbstractMap.SimpleEntry(Y, y))

        override fun get(key: Variable): Double? = when (key) {
            X -> x
            Y -> y
            else -> null
        }
    }

    fun evaluateAt(x: Double, y: Double): Double =
        MathContext.withContext(evaluationContext) { expression.evaluateNumerically(XYBindings(x, y)) }

    /** (∂F/∂x, ∂F/∂y) by central differences. */
    fun gradientAt(x: Double, y: Double): PlotPoint {
        val hx = 1e-6 * max(1.0, abs(x))
        val hy = 1e-6 * max(1.0, abs(y))
        return PlotPoint(
            (evaluateAt(x + hx, y) - evaluateAt(x - hx, y)) / (2.0 * hx),
            (evaluateAt(x, y + hy) - evaluateAt(x, y - hy)) / (2.0 * hy)
        )
    }

    /** dy/dx of the curve at a point on it: -F_x / F_y; infinite where the tangent is vertical. */
    fun slopeAt(x: Double, y: Double): Double {
        val g = gradientAt(x, y)
        if (!g.x.isFinite() || !g.y.isFinite()) return Double.NaN
        if (abs(g.y) <= 1e-9 * abs(g.x)) return Double.POSITIVE_INFINITY
        return -g.x / g.y
    }

    /**
     * Moves ([x], [y]) onto the curve along the gradient (Newton steps). Returns null if it does
     * not converge or ends up further away than [maxDistance], e.g. near a singular point.
     */
    fun projectOntoCurve(x: Double, y: Double, maxDistance: Double = Double.POSITIVE_INFINITY): PlotPoint? {
        var px = x
        var py = y
        val scale = max(1.0, max(abs(x), abs(y)))
        repeat(40) {
            val f = evaluateAt(px, py)
            if (!f.isFinite()) return null
            val g = gradientAt(px, py)
            val norm2 = g.x * g.x + g.y * g.y
            if (!norm2.isFinite() || norm2 == 0.0) return null
            val stepX = f * g.x / norm2
            val stepY = f * g.y / norm2
            px -= stepX
            py -= stepY
            if (hypot(stepX, stepY) <= 1e-12 * scale) {
                return if (hypot(px - x, py - y) <= maxDistance) PlotPoint(px, py) else null
            }
        }
        return null
    }

    companion object {
        val X = Variable("x")
        val Y = Variable("y")

        /** An equation in x and y (y may appear anywhere); null for everything else. */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): ImplicitCurve? {
            if (expr !is Equals) return null
            val vars = expr.variables()
            if (Y !in vars || !vars.all { it == X || it == Y }) return null
            val body = PlotCurve.canonicalForPlotting(Additions(expr.left, expr.right.negate()), context)
            return ImplicitCurve(body, context.angleUnit)
        }
    }
}
