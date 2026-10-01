package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.PlotPoint
import com.vortessence.calculator.core.math.PlotSegment
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Value
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Drawing and analysing implicit curves F(x, y) = 0. */
object ImplicitPlotter {

    /**
     * Approximates the curve by line segments with marching squares: F is sampled on a
     * [cols] × [rows] grid, and wherever it changes sign along a cell edge the crossing is
     * located by bisection. Sign changes at poles (|F| grows instead of shrinking) are ignored.
     * Curves that only touch zero without changing sign, like (x - y)² = 0, are not found.
     *
     * With [logX] / [logY] the grid is spaced evenly in log10 of that coordinate (the range
     * must then be positive), matching a logarithmic axis on screen.
     */
    fun contour(
        curve: ImplicitCurve,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        cols: Int,
        rows: Int,
        context: MathContext? = null,
        logX: Boolean = false,
        logY: Boolean = false
    ): List<PlotSegment> {
        val startX = if (logX) log10(minX) else minX
        val startY = if (logY) log10(minY) else minY
        val dx = ((if (logX) log10(maxX) else maxX) - startX) / cols
        val dy = ((if (logY) log10(maxY) else maxY) - startY) / rows
        fun xAt(i: Int) = if (logX) 10.0.pow(startX + i * dx) else startX + i * dx
        fun yAt(j: Int) = if (logY) 10.0.pow(startY + j * dy) else startY + j * dy

        val values = Array(rows + 1) { DoubleArray(cols + 1) }
        for (j in 0..rows) {
            if (context?.shouldStop == true) return emptyList()
            for (i in 0..cols) values[j][i] = curve.evaluateAt(xAt(i), yAt(j))
        }

        // Each edge is shared by two cells, so its crossing is computed once and cached
        val horizontal = arrayOfNulls<Any>((rows + 1) * cols)
        val vertical = arrayOfNulls<Any>(rows * (cols + 1))
        val none = Any()

        fun crossing(cache: Array<Any?>, index: Int, ax: Double, ay: Double, bx: Double, by: Double, fa: Double, fb: Double): PlotPoint? {
            val cached = cache[index]
            if (cached != null) return cached as? PlotPoint
            val point = edgeCrossing(curve, ax, ay, bx, by, fa, fb)
            cache[index] = point ?: none
            return point
        }

        val segments = mutableListOf<PlotSegment>()
        for (j in 0 until rows) {
            if (context?.shouldStop == true) return emptyList()
            for (i in 0 until cols) {
                val f00 = values[j][i]
                val f10 = values[j][i + 1]
                val f01 = values[j + 1][i]
                val f11 = values[j + 1][i + 1]
                if (!f00.isFinite() || !f10.isFinite() || !f01.isFinite() || !f11.isFinite()) continue
                val s00 = f00 >= 0
                val s10 = f10 >= 0
                val s01 = f01 >= 0
                val s11 = f11 >= 0
                if (s00 == s10 && s00 == s01 && s00 == s11) continue

                val x0 = xAt(i)
                val x1 = xAt(i + 1)
                val y0 = yAt(j)
                val y1 = yAt(j + 1)
                val bottom = if (s00 != s10) crossing(horizontal, j * cols + i, x0, y0, x1, y0, f00, f10) else null
                val top = if (s01 != s11) crossing(horizontal, (j + 1) * cols + i, x0, y1, x1, y1, f01, f11) else null
                val left = if (s00 != s01) crossing(vertical, j * (cols + 1) + i, x0, y0, x0, y1, f00, f01) else null
                val right = if (s10 != s11) crossing(vertical, j * (cols + 1) + i + 1, x1, y0, x1, y1, f10, f11) else null

                val found = listOfNotNull(bottom, right, top, left)
                if (found.size == 4) {
                    // Saddle: the centre decides which corners are cut off from the others
                    val centre = curve.evaluateAt((x0 + x1) / 2.0, (y0 + y1) / 2.0)
                    if ((centre >= 0) == s00) {
                        segments.add(PlotSegment(bottom!!, right!!))
                        segments.add(PlotSegment(top!!, left!!))
                    } else {
                        segments.add(PlotSegment(left!!, bottom!!))
                        segments.add(PlotSegment(right!!, top!!))
                    }
                } else if (found.size >= 2) {
                    segments.add(PlotSegment(found[0], found[1]))
                }
            }
        }
        return segments
    }

    private fun edgeCrossing(
        curve: ImplicitCurve,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        fa: Double,
        fb: Double
    ): PlotPoint? {
        var lo = 0.0
        var hi = 1.0
        var flo = fa
        repeat(16) {
            val mid = (lo + hi) / 2.0
            val fmid = curve.evaluateAt(ax + (bx - ax) * mid, ay + (by - ay) * mid)
            if (!fmid.isFinite()) return null
            if ((fmid >= 0) == (flo >= 0)) {
                lo = mid
                flo = fmid
            } else {
                hi = mid
            }
        }
        val t = (lo + hi) / 2.0
        val x = ax + (bx - ax) * t
        val y = ay + (by - ay) * t
        val f = curve.evaluateAt(x, y)
        // At a true crossing |F| stays below the corner values; at a pole it grows beyond them
        if (!f.isFinite() || abs(f) > max(abs(fa), abs(fb))) return null
        return PlotPoint(x, y)
    }

    /** Where the curve crosses the x-axis (y = 0) within [minX, maxX]. */
    fun xIntercepts(curve: ImplicitCurve, minX: Double, maxX: Double, context: MathContext? = null): List<Double> {
        val onAxis = Function1D(curve.expression.substitute(ImplicitCurve.Y, Value.ZERO), ImplicitCurve.X, curve.angleUnit)
        return NumericRootFinder.findRoots(onAxis, minX, maxX, 400, context)
    }

    /** Where the curve crosses the y-axis (x = 0) within [minY, maxY]. */
    fun yIntercepts(curve: ImplicitCurve, minY: Double, maxY: Double, context: MathContext? = null): List<Double> {
        val onAxis = Function1D(curve.expression.substitute(ImplicitCurve.X, Value.ZERO), ImplicitCurve.Y, curve.angleUnit)
        return NumericRootFinder.findRoots(onAxis, minY, maxY, 400, context)
    }

    /**
     * Points with a horizontal tangent (F_x = 0), split into local maxima and minima of y.
     * Found by looking for sign changes of F_x along the [segments] of [contour].
     */
    fun horizontalTangents(
        curve: ImplicitCurve,
        segments: List<PlotSegment>,
        context: MathContext? = null
    ): Pair<List<PlotPoint>, List<PlotPoint>> {
        val maxima = mutableListOf<PlotPoint>()
        val minima = mutableListOf<PlotPoint>()
        for ((index, segment) in segments.withIndex()) {
            if (index % 100 == 0 && context?.shouldStop == true) break
            val g0 = curve.gradientAt(segment.start.x, segment.start.y).x
            val g1 = curve.gradientAt(segment.end.x, segment.end.y).x
            if (!g0.isFinite() || !g1.isFinite()) continue
            // A zero exactly at a shared endpoint (e.g. on a grid line) counts for one segment end only
            val zeroAtEnd = (g0 == 0.0) != (g1 == 0.0)
            if (g0 * g1 >= 0.0 && !zeroAtEnd) continue

            // Bisect along the segment, keeping each probe on the curve
            val length = hypot(segment.end.x - segment.start.x, segment.end.y - segment.start.y)
            var lo = 0.0
            var hi = 1.0
            var glo = g0
            var point: PlotPoint? = when {
                g0 == 0.0 -> segment.start
                g1 == 0.0 -> segment.end
                else -> null
            }
            for (iteration in 0 until 40) {
                if (zeroAtEnd) break
                val mid = (lo + hi) / 2.0
                val probe = curve.projectOntoCurve(
                    segment.start.x + (segment.end.x - segment.start.x) * mid,
                    segment.start.y + (segment.end.y - segment.start.y) * mid,
                    maxDistance = length
                ) ?: break
                point = probe
                val g = curve.gradientAt(probe.x, probe.y).x
                if (g * glo > 0.0) {
                    lo = mid
                    glo = g
                } else {
                    hi = mid
                }
            }
            val p = point ?: continue

            // On the curve y'' = -F_xx / F_y where y' = 0
            val fy = curve.gradientAt(p.x, p.y).y
            val h = 1e-4 * max(1.0, abs(p.x))
            val fxx = (curve.evaluateAt(p.x + h, p.y) - 2.0 * curve.evaluateAt(p.x, p.y) + curve.evaluateAt(p.x - h, p.y)) / (h * h)
            if (!fy.isFinite() || abs(fy) < 1e-9 || !fxx.isFinite()) continue
            val target = if (-fxx / fy < 0) maxima else minima
            val tolerance = 1e-6 * max(1.0, max(abs(p.x), abs(p.y)))
            if ((maxima + minima).none { abs(it.x - p.x) < tolerance && abs(it.y - p.y) < tolerance }) target.add(p)
        }
        return maxima to minima
    }
}
