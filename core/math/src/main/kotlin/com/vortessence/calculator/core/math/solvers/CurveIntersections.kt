package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.PlotPoint
import com.vortessence.calculator.core.math.PlotSegment
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The points where two curves of the graph cross, as numbers: functions y = f(x) and implicit curves F(x, y) = 0 (the boundary
 * of an inequality counts as the curve it is). Only points inside the given range are looked for; a curve that only touches
 * the other is found if the root finder or the sign change at the grid sees it.
 */
object CurveIntersections {
    private val X = Variable("x")
    private val Y = Variable("y")

    /** The function or implicit curve [curve] draws, or null (a surface, several inequalities). */
    fun boundaryOf(curve: PlotCurve): PlotCurve? = when (curve) {
        is Function1D, is ImplicitCurve -> curve
        // In one variable (x² > 1) the boundary is a pair of vertical lines: the curve drawn for it is only the difference, no boundary
        is InequalityPlot -> if (curve.twoVariables) curve.primary?.let { boundaryOf(it) } else null
        else -> null
    }

    /** The crossings of [a] and [b] within the range, sorted by x then y; empty if there are none or the pair is not supported. */
    fun find(
        a: PlotCurve,
        b: PlotCurve,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        context: MathContext? = null,
        steps: Int = 800,
        /** The traced lines of implicit curves for this range, kept by the caller so that a curve in several pairs is traced once. */
        contours: MutableMap<ImplicitCurve, List<PlotSegment>> = mutableMapOf()
    ): List<PlotPoint> {
        val first = boundaryOf(a) ?: return emptyList()
        val second = boundaryOf(b) ?: return emptyList()
        if (!(minX < maxX && minY < maxY)) return emptyList()
        val found = when {
            first is Function1D && second is Function1D -> functions(first, second, minX, maxX, steps, context)
            first is Function1D && second is ImplicitCurve -> functionWithImplicit(first, second, minX, maxX, steps, context)
            first is ImplicitCurve && second is Function1D -> functionWithImplicit(second, first, minX, maxX, steps, context)
            first is ImplicitCurve && second is ImplicitCurve ->
                implicits(first, second, contours.getOrPut(first) { ImplicitPlotter.contour(first, minX, maxX, minY, maxY, GRID, GRID, context) }, minX, maxX, minY, maxY, context)
            else -> emptyList()
        }
        return found
            .filter { it.x.isFinite() && it.y.isFinite() && it.x in minX..maxX && it.y in minY..maxY }
            .sortedWith(compareBy({ it.x }, { it.y }))
    }

    /** f(x) = g(x): the roots of f − g. */
    private fun functions(f: Function1D, g: Function1D, minX: Double, maxX: Double, steps: Int, context: MathContext?): List<PlotPoint> {
        val variable = f.variable
        val other = g.expression.substituteVariables(mapOf(g.variable to variable))
        val difference = Function1D(Additions(f.expression, other.negate()), variable, f.angleUnit)
        return NumericRootFinder.findRoots(difference, minX, maxX, steps, context).map { PlotPoint(it, f.evaluateAt(it)) }
    }

    /** F(x, f(x)) = 0: the roots of F with f put in for y. */
    private fun functionWithImplicit(f: Function1D, curve: ImplicitCurve, minX: Double, maxX: Double, steps: Int, context: MathContext?): List<PlotPoint> {
        // A function of y alone has no x to be a function of
        if (f.variable == Y) return emptyList()
        val fx = f.expression.substituteVariables(mapOf(f.variable to X))
        val composed = curve.expression.substituteVariables(mapOf(Y to fx))
        val difference = Function1D(composed, X, curve.angleUnit)
        return NumericRootFinder.findRoots(difference, minX, maxX, steps, context).map { PlotPoint(it, f.evaluateAt(it)) }
    }

    /**
     * F(x, y) = 0 and G(x, y) = 0: wherever G changes sign along the line segments of the curve F (marching squares), the
     * crossing is located on the segment and then made exact by Newton's method on both equations.
     */
    private fun implicits(
        f: ImplicitCurve, g: ImplicitCurve, segments: List<PlotSegment>,
        minX: Double, maxX: Double, minY: Double, maxY: Double, context: MathContext?
    ): List<PlotPoint> {
        if (coincide(g, segments, minX, maxX, minY, maxY)) return emptyList()
        val scale = max(maxX - minX, maxY - minY)
        val found = mutableListOf<PlotPoint>()
        for (segment in segments) {
            if (context?.shouldStop == true) break
            val ga = g.evaluateAt(segment.start.x, segment.start.y)
            val gb = g.evaluateAt(segment.end.x, segment.end.y)
            if (!ga.isFinite() || !gb.isFinite() || ga * gb > 0.0 || (ga == 0.0 && gb == 0.0)) continue
            // Bisection along the segment
            var t0 = 0.0
            var t1 = 1.0
            var g0 = ga
            repeat(40) {
                val tm = (t0 + t1) / 2.0
                val gm = g.evaluateAt(lerp(segment.start.x, segment.end.x, tm), lerp(segment.start.y, segment.end.y, tm))
                if (!gm.isFinite()) return@repeat
                if (g0 * gm <= 0.0) t1 = tm else {
                    t0 = tm
                    g0 = gm
                }
            }
            val t = (t0 + t1) / 2.0
            val start = PlotPoint(lerp(segment.start.x, segment.end.x, t), lerp(segment.start.y, segment.end.y, t))
            val point = newton(f, g, start, scale * 1e-3) ?: continue
            // A pole of G looks like a sign change: G must really be zero there
            if (abs(g.evaluateAt(point.x, point.y)) > 1e-6 * max(1.0, abs(ga) + abs(gb))) continue
            if (found.none { hypot(it.x - point.x, it.y - point.y) <= scale * 1e-5 }) found.add(point)
        }
        return found
    }

    /**
     * Whether [g] is zero along the [segments] of the other curve: the same curve, however it is written (x² + y² = 4 and
     * 2x² + 2y² = 8). Rounding noise would then make G change sign here and there, and every such place look like a crossing.
     * The ends of the segments lie on the curve to within a small part of a grid cell (they are found by bisection), so what is
     * measured is how far they are from the curve of G: |G| / |∇G|, which is about the same as the distance.
     */
    private fun coincide(g: ImplicitCurve, segments: List<PlotSegment>, minX: Double, maxX: Double, minY: Double, maxY: Double): Boolean {
        if (segments.isEmpty()) return false
        val cell = max(maxX - minX, maxY - minY) / GRID
        var on = 0
        var measured = 0
        for (segment in segments) {
            val value = g.evaluateAt(segment.start.x, segment.start.y)
            val gradient = g.gradientAt(segment.start.x, segment.start.y)
            val slope = hypot(gradient.x, gradient.y)
            if (!value.isFinite() || !slope.isFinite() || slope == 0.0) continue
            measured++
            if (abs(value) / slope <= cell * 1e-3) on++
        }
        // Most of the curve lies on G's curve: crossings along it would only be noise
        return measured > 0 && on >= measured * 0.6
    }

    /** Newton's method for F = G = 0 from [start]; null if it does not converge or leaves the neighbourhood of the start by more than [reach]. */
    private fun newton(f: ImplicitCurve, g: ImplicitCurve, start: PlotPoint, reach: Double): PlotPoint? {
        var x = start.x
        var y = start.y
        repeat(30) {
            val fv = f.evaluateAt(x, y)
            val gv = g.evaluateAt(x, y)
            if (!fv.isFinite() || !gv.isFinite()) return null
            val fg = f.gradientAt(x, y)
            val gg = g.gradientAt(x, y)
            val det = fg.x * gg.y - fg.y * gg.x
            if (!det.isFinite() || det == 0.0) return if (abs(fv) < 1e-12 && abs(gv) < 1e-12) PlotPoint(x, y) else null
            val dx = (fv * gg.y - gv * fg.y) / det
            val dy = (gv * fg.x - fv * gg.x) / det
            x -= dx
            y -= dy
            if (hypot(x - start.x, y - start.y) > reach * 10.0) return null
            if (hypot(dx, dy) <= 1e-12 * max(1.0, max(abs(x), abs(y)))) return PlotPoint(x, y)
        }
        return null
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /** The grid the first implicit curve is traced on: fine enough for curves that cross at a shallow angle. */
    private const val GRID = 260
}
